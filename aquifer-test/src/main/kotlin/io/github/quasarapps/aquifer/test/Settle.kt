package io.github.quasarapps.aquifer.test

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * Runs every task already scheduled on this [TestScope]'s scheduler — including the follow-up
 * tasks that work itself schedules — until the queue at the current virtual time is empty: the
 * scheduling half of Aquifer's deterministic testing.
 *
 * Under `runTest`, fire-and-forget work (a [prefetch][io.github.quasarapps.aquifer.Aquifer.prefetch],
 * a stale-while-revalidate refresh) lands on the store's scope rather than running inline, so the
 * scheduler must be driven before asserting on its effects:
 *
 * ```
 * store.prefetch("ada")
 * settle()
 * assertEquals(1, store.fetchCount("ada"))
 * ```
 *
 * This drains only the scheduler the receiver owns, so it settles a store whose `scope` was built
 * on it — pass `backgroundScope` (or the `TestScope` itself) when constructing the store or
 * [fakeAquifer]; a store running on its own dispatcher has nothing shared to drain. It does
 * **not** advance the test's virtual clock: work gated on a delay (e.g. a `prefetch` of a key
 * scripted with a fetch delay) needs `advanceUntilIdle()`/`advanceTimeBy(...)` instead of (or in
 * addition to) `settle()`.
 */
public fun TestScope.settle() {
    runCurrent()
}
