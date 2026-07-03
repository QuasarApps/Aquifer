package io.github.quasarapps.aquifer.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A fencing epoch for one key: a store-global counter plus the key's own bump count. A fetch
 * captures one when it registers and compares it (unchanged) when it commits, so any mutation that
 * lands in between moves the epoch and drops the commit. Structural equality is the whole point —
 * a captured [Epoch] is "still current" only if both components match.
 */
internal data class Epoch(val global: Long, val key: Long)

/**
 * The outcome of a single-flight registration attempt ([EpochFence.beginOrJoin]).
 *
 * [T] is the in-flight token type (a `Deferred` in the engine).
 */
internal sealed interface Registration<out T> {
    /** This caller won the slot: [token] is now registered, and [epoch] was captured for it. */
    class Started<T>(val token: T, val epoch: Epoch) : Registration<T>

    /**
     * A fetch was already in flight: join [existing]. [discarded] is the token this caller built
     * before losing the race — the caller must dispose it (e.g. cancel the `Deferred`) — or `null`
     * when it joined on the fast path without building one.
     */
    class Joined<T>(val existing: T, val discarded: T?) : Registration<T>
}

/**
 * The epoch clock that fences stale fetch commits, together with the single-flight registry it
 * drives. This is the heart of the "never resurrect deleted/edited data" guarantee.
 *
 * A fetch captures an [Epoch] for its key when it registers ([beginOrJoin]); its commit is accepted
 * only if [isCurrent] still holds when it lands. A mutation calls [fence] to bump the key's epoch
 * (and evict any in-flight fetch); [fenceAll] bumps the store-global counter and clears everything.
 * Either way, a fetch that captured an earlier epoch is dropped instead of overwriting the write
 * that fenced it.
 *
 * Extracted from `RealAquifer` (with the registry it composes) so the exact fencing code is what a
 * Lincheck check drives. [T] is the in-flight token type.
 *
 * **Locking.** [capture]/[beginOrJoin] and the registry race are lock-free by design. [fence],
 * [fenceAll], and the commit gate [isCurrent] are **not** internally synchronized: the engine calls
 * them under its single commit `Mutex` — the same lock that guards the memory/persistence write
 * they decide — so the epoch check and the write it authorizes are one atomic step.
 */
internal class EpochFence<K, T> {
    private val inFlight = SingleFlightRegistry<K, T>()
    private val globalEpoch = AtomicLong()
    private val keyEpochs = ConcurrentHashMap<K, Long>()

    /** Lock-free epoch snapshot for [key]; a fetch captures one at registration. */
    fun capture(key: K): Epoch = Epoch(globalEpoch.get(), keyEpochs[key] ?: 0L)

    /**
     * Whether [key]'s epoch is unchanged since [captured]. Called under the commit lock, so a
     * `true` result means the commit that follows in the same critical section is not fenced.
     */
    fun isCurrent(key: K, captured: Epoch): Boolean = capture(key) == captured

    /**
     * Fences [key]: bumps its epoch and evicts any in-flight fetch, so a fetch that captured an
     * earlier epoch fails [isCurrent] and later reads start a fresh fetch. Must run under the
     * commit lock.
     */
    fun fence(key: K) {
        keyEpochs[key] = (keyEpochs[key] ?: 0L) + 1L
        inFlight.evict(key)
    }

    /**
     * Fences the whole store: bumps the global epoch and clears the per-key epochs and the
     * in-flight registry, so every captured epoch is stale at once. Must run under the commit lock.
     */
    fun fenceAll() {
        globalEpoch.incrementAndGet()
        keyEpochs.clear()
        inFlight.clear()
    }

    /**
     * Single-flight registration with **capture-before-register**: peeks for an in-flight fetch and
     * joins it if present; otherwise captures the epoch *before* publishing the new token, so a
     * [fence] landing in the gap leaves the captured epoch stale and the eventual commit is dropped.
     * Capturing *after* registration would let such a fetch read the post-fence epoch and clobber
     * the very write that fenced it — the #42 regression this ordering exists to prevent.
     *
     * [make] builds the token from the captured epoch; it is invoked at most once, and only after a
     * peek miss — so the fast-path join never allocates one.
     */
    fun beginOrJoin(key: K, make: (Epoch) -> T): Registration<T> {
        inFlight.peek(key)?.let { return Registration.Joined(it, discarded = null) }
        val epoch = capture(key)
        val token = make(epoch)
        val existing = inFlight.registerIfAbsent(key, token)
        return if (existing != null) {
            Registration.Joined(existing, discarded = token)
        } else {
            Registration.Started(token, epoch)
        }
    }

    /** Fetch-completion hook: drops [key]'s registration only if it still maps to [token]. */
    fun completed(key: K, token: T) {
        inFlight.remove(key, token)
    }

    /** Whether a fetch is currently in flight for [key]. */
    fun hasInFlight(key: K): Boolean = inFlight.contains(key)

    /** A view of the keys with a fetch in flight (a candidate source for invalidateWhere). */
    fun inFlightKeys(): Set<K> = inFlight.keys()

    /** The number of fetches currently in flight (the stats in-flight gauge). */
    val inFlightSize: Int get() = inFlight.size

    /** A view of the keys carrying a per-key epoch bump (a candidate source for invalidateWhere). */
    fun fencedKeys(): Set<K> = keyEpochs.keys
}
