package io.github.quasarapps.aquifer.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic contract check of [EpochFence] — the epoch-clock/single-flight primitive extracted
 * from `RealAquifer`. The concurrency guarantee (that a fetch which captured an earlier epoch is
 * fenced off) is a real-time property covered by `MutationFencingTest`/`FenceDuringRegistrationTest`
 * on the real engine and by the linearizability check on [SingleFlightRegistry]; this pins the
 * single-threaded semantics of the extracted API directly.
 */
class EpochFenceTest {

    @Test
    fun `capture is stable until a fence moves it`() {
        val fence = EpochFence<String, String>()
        val captured = fence.capture("k")
        assertEquals(Epoch(0L, 0L), captured)
        assertTrue(fence.isCurrent("k", captured))

        fence.fence("k")
        assertFalse(fence.isCurrent("k", captured), "a fenced key's earlier epoch is no longer current")
        assertEquals(Epoch(0L, 1L), fence.capture("k"))
    }

    @Test
    fun `fence is per key`() {
        val fence = EpochFence<String, String>()
        val other = fence.capture("other")
        fence.fence("k")
        assertTrue(fence.isCurrent("other", other), "fencing one key must not move another key's epoch")
    }

    @Test
    fun `fenceAll moves every captured epoch at once`() {
        val fence = EpochFence<String, String>()
        val a = fence.capture("a")
        val b = fence.capture("b")

        fence.fenceAll()

        assertFalse(fence.isCurrent("a", a))
        assertFalse(fence.isCurrent("b", b))
        assertEquals(Epoch(1L, 0L), fence.capture("a"), "fenceAll bumps the global component and resets per-key")
    }

    @Test
    fun `beginOrJoin starts once then joins`() {
        val fence = EpochFence<String, String>()

        val first = fence.beginOrJoin("k") { "token-1" }
        assertIs<Registration.Started<String>>(first)
        assertEquals("token-1", first.token)
        assertEquals(Epoch(0L, 0L), first.epoch)
        assertTrue(fence.hasInFlight("k"))

        val second = fence.beginOrJoin("k") { error("must not build a token when a fetch is in flight") }
        assertIs<Registration.Joined<String>>(second)
        assertEquals("token-1", second.existing)
        assertNull(second.discarded, "a fast-path join builds no token to discard")
    }

    @Test
    fun `fence evicts the in-flight fetch so the next begin starts fresh`() {
        val fence = EpochFence<String, String>()
        fence.beginOrJoin("k") { "token-1" }

        fence.fence("k")
        assertFalse(fence.hasInFlight("k"), "fencing evicts the in-flight registration")

        val next = fence.beginOrJoin("k") { "token-2" }
        assertIs<Registration.Started<String>>(next)
        assertEquals(Epoch(0L, 1L), next.epoch, "the new fetch captures the post-fence epoch")
    }

    @Test
    fun `completed clears only the matching token`() {
        val fence = EpochFence<String, String>()
        fence.beginOrJoin("k") { "token-1" }

        fence.completed("k", "stale-token")
        assertTrue(fence.hasInFlight("k"), "a non-matching completion leaves the registration in place")

        fence.completed("k", "token-1")
        assertFalse(fence.hasInFlight("k"))
    }
}
