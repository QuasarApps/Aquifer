package io.github.quasarapps.aquifer.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins the two halves of [settle]'s contract: it drains *everything* scheduled at the current
 * virtual time, however many scheduler hops that takes, and it advances *no* virtual time.
 */
class SettleTest {

    /**
     * Regression guard against a bounded drain (the previous implementation was eight `yield()`
     * calls, each of which advances a sequential chain like this by exactly one hop, so it would
     * finish 8 of the 32 links). A launch chain is the discriminating workload: every link only
     * schedules the next once it has itself run.
     */
    @Test
    fun `settle drains work chains deeper than any fixed yield bound`() = runTest {
        var hops = 0
        fun CoroutineScope.hop() {
            launch {
                hops++
                if (hops < 32) hop()
            }
        }
        backgroundScope.hop()

        settle()

        assertEquals(32, hops, "settle() must drain the whole chain, not a fixed number of hops")
    }

    @Test
    fun `settle leaves delay-gated work pending`() = runTest {
        var ran = false
        backgroundScope.launch {
            delay(1.milliseconds)
            ran = true
        }

        settle()
        assertFalse(ran, "settle() must not advance virtual time")

        // advanceTimeBy, not advanceUntilIdle: the latter stops once only background-scope tasks
        // remain, so it would never fire a delay whose sole owner is background work.
        advanceTimeBy(2.milliseconds)
        assertTrue(ran, "the same work runs once time is advanced")
    }
}
