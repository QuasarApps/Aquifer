package io.github.quasarapps.aquifer.test

import kotlinx.coroutines.yield

/**
 * Suspends the current coroutine long enough for fire-and-forget work already scheduled on a
 * background scope to run to completion — the scheduling half of Aquifer's deterministic testing.
 *
 * Under `runTest`, background work (a [prefetch][io.github.quasarapps.aquifer.Aquifer.prefetch],
 * a stale-while-revalidate refresh) only runs while the test coroutine itself is suspended, so an
 * explicit suspension point is needed before asserting on its effects:
 *
 * ```
 * store.prefetch("ada")
 * settle()
 * assertEquals(1, store.fetchCount("ada"))
 * ```
 */
public suspend fun settle() {
    repeat(SETTLE_YIELDS) { yield() }
}

private const val SETTLE_YIELDS = 8
