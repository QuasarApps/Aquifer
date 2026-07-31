package io.github.quasarapps.aquifer.internal

import kotlin.time.Duration

/**
 * The per-call `maxAge` bars held by the stream collectors currently active on one key, as a
 * multiset: two collectors asking for the same bar must both be counted, so the last one to
 * detach is the one that drops it.
 *
 * `null` is a bar in its own right — "no per-call override", i.e. judge me by the entry's
 * server-declared horizon or the store TTL — and is stored like any other.
 *
 * **Immutable by design.** `RealAquifer` swaps instances with `ConcurrentHashMap.replace`, the
 * API-21-safe compare-and-set it uses in place of `merge`/`compute`; mutating in place would make
 * that CAS meaningless. Equality is left at identity for the same reason: a losing CAS should
 * retry against whatever the winner installed rather than silently succeed against an equal-looking
 * snapshot.
 *
 * Instances are small — one entry per *distinct* bar on a key, which in practice is one.
 */
internal class ActiveBars private constructor(private val counts: Map<Duration?, Int>) {

    /** The distinct bars in play, for a caller that needs to test staleness against each. */
    val bars: Set<Duration?> get() = counts.keys

    /** A copy with one more collector holding [maxAge]. */
    fun plus(maxAge: Duration?): ActiveBars =
        ActiveBars(counts + (maxAge to (counts[maxAge] ?: 0) + 1))

    /**
     * A copy with one collector holding [maxAge] removed, or `null` when that was the last
     * collector on the key — the signal to drop the key from the active map entirely.
     *
     * Removing a bar that isn't held leaves the multiset untouched (returning a copy, so the
     * caller's CAS still behaves): unregistration is driven by a `finally` paired with a
     * successful registration, so this cannot happen, and quietly ignoring it is safer than
     * corrupting the counts if it ever did.
     */
    fun minus(maxAge: Duration?): ActiveBars? {
        val held = counts[maxAge] ?: return if (counts.isEmpty()) null else ActiveBars(counts)
        val remaining = if (held <= 1) counts - maxAge else counts + (maxAge to held - 1)
        return if (remaining.isEmpty()) null else ActiveBars(remaining)
    }

    companion object {
        /** The bars for a key that just gained its first collector. */
        fun of(maxAge: Duration?): ActiveBars = ActiveBars(mapOf(maxAge to 1))
    }
}
