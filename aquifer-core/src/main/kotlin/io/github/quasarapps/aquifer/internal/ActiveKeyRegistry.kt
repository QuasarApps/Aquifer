package io.github.quasarapps.aquifer.internal

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The keys with active, fetch-capable stream collectors, each mapped to the `maxAge` bars its
 * collectors are holding it to. `revalidateActive` judges staleness against those bars rather than
 * the store-wide TTL alone, so a `stream(key, maxAge = 30.seconds)` is swept on the horizon its
 * caller asked for.
 *
 * Extracted from `RealAquifer` — like [EpochFence] before it — so the exact hand-rolled
 * compare-and-set loops are what a Lincheck check drives, rather than an approximation of them.
 *
 * **Locking: none.** [register]/[unregister] are lock-free and run on collector threads as streams
 * come and go, with no engine lock held. They are CAS loops over `ConcurrentMap` primitives rather
 * than `merge`/`compute`, because those defaults are Android API 24+ and `aquifer-core` supports
 * API 21. [ActiveBars] is immutable, which is what keeps `replace(key, old, new)` a genuine
 * compare-and-set; its identity equality means a losing CAS retries against whatever the winner
 * installed instead of succeeding against an equal-looking snapshot.
 */
internal class ActiveKeyRegistry<K : Any> {

    private val active = ConcurrentHashMap<K, ActiveBars>()

    /** Records one more collector on [key] holding it to [maxAge]. */
    fun register(key: K, maxAge: Duration?) {
        while (true) {
            val current = active[key]
            when {
                current == null -> if (active.putIfAbsent(key, ActiveBars.of(maxAge)) == null) return
                else -> if (active.replace(key, current, current.plus(maxAge))) return
            }
        }
    }

    /**
     * Drops one collector on [key] holding it to [maxAge], removing the key entirely when that was
     * its last. A key with no collectors is absent rather than present-and-empty, so [keys] and
     * [snapshot] never report a key nothing is watching.
     */
    fun unregister(key: K, maxAge: Duration?) {
        while (true) {
            val current = active[key] ?: return
            val remaining = current.minus(maxAge)
            when {
                remaining == null -> if (active.remove(key, current)) return
                else -> if (active.replace(key, current, remaining)) return
            }
        }
    }

    /** The bars held on [key], or `null` when nothing is watching it. Reads one map entry. */
    fun bars(key: K): ActiveBars? = active[key]

    /**
     * A stable copy of the active set with each key's bars — the sweep iterates this rather than
     * the live map, so the keys it judges are exactly the keys it read.
     *
     * Per-key state is exact; the *set* is not a point-in-time snapshot. Like [keys] it is built
     * by iterating a `ConcurrentHashMap`, so a registration racing the copy may or may not appear
     * and two multi-key reads can disagree about a key being registered concurrently. Every caller
     * is best-effort by nature — the revalidation sweep refreshes what it happened to see — so this
     * is a deliberate weakening, not an oversight. Do not build anything on these two that needs an
     * atomic view across keys.
     */
    fun snapshot(): Map<K, ActiveBars> = LinkedHashMap(active)

    /** A weakly-consistent view of the active keys; see [snapshot] on what that does not promise. */
    fun keys(): Set<K> = active.keys
}
