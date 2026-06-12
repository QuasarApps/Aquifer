package io.github.quasarapps.aquifer

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes

/** Per-call `maxAge`: overrides the store TTL for one read or stream, never the cache itself. */
class FreshnessOverrideTest {

    @Test
    fun `a tighter maxAge forces a refetch the store TTL would not`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 10.minutes }
        }
        store.put("k", 100)
        clock.advanceBy(2.minutes) // fresh by store policy (10m), stale for a 1m caller

        assertEquals(1, store.get("k", maxAge = 1.minutes))
        assertEquals(1, calls)
    }

    @Test
    fun `a looser maxAge serves an entry the store considers stale`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)
        clock.advanceBy(5.minutes) // stale by store policy, acceptable to a 10m caller

        assertEquals(100, store.get("k", maxAge = 10.minutes))
        assertEquals(0, calls)
    }

    @Test
    fun `maxAge colors a stream's staleness flags and revalidation`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 10.minutes }
        }
        store.put("k", 100)
        clock.advanceBy(2.minutes)

        store.stream("k", maxAge = 1.minutes).test {
            // Stale for THIS collector even though the store-wide TTL says fresh.
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = true), awaitItem())
            assertEquals(DataState.Loading(100), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a parallel stream without the override is untouched`() = runTest {
        val clock = FakeClock()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { -1 }
            freshness { timeToLive = 10.minutes }
        }
        store.put("k", 100)
        clock.advanceBy(2.minutes)

        store.stream("k").test {
            // Store policy applies here: fresh, no revalidation.
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())
            settle()
            expectNoEvents()
        }
    }

    @Test
    fun `staleness-blind strategies keep their fetch behavior regardless of maxAge`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 10.minutes }
        }
        store.put("k", 100)
        clock.advanceBy(5.minutes)

        // CacheOnly serves regardless of age, exactly as without the override.
        assertEquals(100, store.get("k", Freshness.CacheOnly, maxAge = 1.minutes))
        settle()
        assertEquals(0, calls)

        // NetworkFirst and NetworkOnly always fetch — a generous maxAge changes nothing.
        assertEquals(1, store.get("k", Freshness.NetworkFirst, maxAge = 10.minutes))
        assertEquals(2, store.get("k", Freshness.NetworkOnly, maxAge = 10.minutes))
        assertEquals(2, calls)
    }

    @Test
    fun `a cache-only stream's staleness flags follow the caller's maxAge`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 10.minutes }
        }
        store.put("k", 100)
        clock.advanceBy(2.minutes)

        // Fetch behavior untouched (no fetch), but isStale is colored by THIS caller's bar.
        store.stream("k", Freshness.CacheOnly, maxAge = 1.minutes).test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = true), awaitItem())
            settle()
            expectNoEvents()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `an infinite maxAge means serve anything cached, fetch only on miss`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)
        clock.advanceBy(10_000.minutes) // ancient by store policy

        assertEquals(100, store.get("k", maxAge = Duration.INFINITE))
        settle()
        assertEquals(0, calls)

        // A genuine miss still fetches.
        assertEquals(1, store.get("other", maxAge = Duration.INFINITE))
        assertEquals(1, calls)
    }

    @Test
    fun `sub-millisecond horizons do not truncate to instantly stale`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
        }
        store.put("k", 100)

        // Same clock tick: elapsed is 0 ms, which is within a 500µs horizon — not stale.
        assertEquals(100, store.get("k", maxAge = 500.microseconds))
        settle()
        assertEquals(0, calls)
    }

    @Test
    fun `maxAge must be positive`() = runTest {
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            fetcher { 1 }
        }

        assertFailsWith<IllegalArgumentException> { store.get("k", maxAge = (-1).minutes) }
        assertFailsWith<IllegalArgumentException> { store.stream("k", maxAge = Duration.ZERO) }
    }
}
