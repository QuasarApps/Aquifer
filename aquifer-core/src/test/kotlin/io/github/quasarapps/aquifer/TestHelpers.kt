package io.github.quasarapps.aquifer

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * Runs every task already scheduled on this [TestScope]'s scheduler — including follow-up tasks
 * that work itself schedules — until the queue at the current virtual time is empty.
 *
 * Fire-and-forget effects (a stale-while-revalidate refresh, a prefetch) land on the store's
 * injected scope rather than running inline, so the scheduler must be driven before asserting on
 * them. Unlike the yield-loop this replaces, draining is complete by construction: work needing
 * more scheduler hops than a fixed yield count cannot silently satisfy a negative assertion. It
 * does not advance virtual time — delay-gated work still needs `advanceUntilIdle()`.
 */
fun TestScope.settle() {
    runCurrent()
}
