package io.github.quasarapps.aquifer.internal

import java.util.concurrent.ConcurrentHashMap

/**
 * The single-flight registry: at most one in-flight fetch token per key, so concurrent reads for
 * the same key share one backend call instead of stampeding it. A thin, lock-free wrapper over a
 * [ConcurrentHashMap] whose operations are exactly the compare-and-set steps the engine relies on:
 * [registerIfAbsent] (win-the-slot or join), [remove] (drop only the token still mapped — the
 * fetch-completion hook), and [evict] (drop unconditionally — the fencing hook).
 *
 * Extracted from `RealAquifer` so the registry that backs the fetch dedup is the same code a
 * Lincheck linearizability check drives directly. [T] is the in-flight token type (a `Deferred` in
 * the engine); the registry only stores it and hands it back, never runs it.
 */
internal class SingleFlightRegistry<K, T> {
    private val inFlight = ConcurrentHashMap<K, T>()

    /** The token currently registered for [key], or `null` when no fetch is in flight. */
    fun peek(key: K): T? = inFlight[key]

    /** Whether a fetch is currently registered for [key]. */
    fun contains(key: K): Boolean = inFlight.containsKey(key)

    /**
     * Atomically registers [token] as the in-flight fetch for [key], but only if none is
     * registered yet. Returns `null` when [token] won the slot, or the already-registered token
     * when the caller lost the race and should join that one instead.
     */
    fun registerIfAbsent(key: K, token: T): T? = inFlight.putIfAbsent(key, token)

    /**
     * Drops [key]'s registration only if it still maps to [token] — the completion hook, so a
     * finishing fetch never evicts a *newer* fetch that already replaced it. Returns whether a
     * registration was removed.
     */
    fun remove(key: K, token: T): Boolean = inFlight.remove(key, token)

    /**
     * Unconditionally drops any in-flight registration for [key] — the fencing hook, so later
     * reads cannot join a fetch whose commit is about to be discarded.
     */
    fun evict(key: K) {
        inFlight.remove(key)
    }

    /** Drops every registration at once (invalidateAll). */
    fun clear() {
        inFlight.clear()
    }

    /** A view of the keys with a fetch in flight (a candidate source for invalidateWhere). */
    fun keys(): Set<K> = inFlight.keys

    /** The number of fetches currently in flight (the stats in-flight gauge). */
    val size: Int get() = inFlight.size
}
