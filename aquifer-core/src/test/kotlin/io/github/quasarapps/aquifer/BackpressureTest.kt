package io.github.quasarapps.aquifer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class BackpressureTest {

    @Test
    fun `a stalled stream collector does not block writers or other callers`() = runTest {
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            fetcher { -1 }
        }
        store.put("k", 0)

        // A collector that hangs forever on its first item: its downstream applies maximal
        // backpressure, which must not propagate into the store's update bus.
        val gate = CompletableDeferred<Unit>()
        backgroundScope.launch {
            store.stream("k").collect { gate.await() }
        }
        settle() // The collector is now subscribed and stuck.

        // Far more writes than any internal buffer; these must complete without suspending
        // on the stalled collector. The timeout only fires if a put hangs.
        withTimeout(5_000) {
            repeat(500) { store.put("k", it) }
        }

        assertEquals(499, store.get("k", Freshness.CacheOnly))
        // Fetches for unrelated keys complete as well — the engine is not stalled.
        assertEquals(-1, store.get("unrelated"))
    }
}
