package io.github.quasarapps.aquifer

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** `revalidateActive(force = true)`: what it overrides, and what it deliberately does not. */
class RevalidateForceTest {

    @Test
    fun `force refreshes a fresh key the unforced sweep would skip`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.hours }
        }
        store.put("k", 100)

        store.stream("k").test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            store.revalidateActive() // fresh by every bar: nothing happens
            settle()
            expectNoEvents()
            assertEquals(0, calls)

            store.revalidateActive(force = true) // the user pulled the list down

            assertEquals(DataState.Loading(100), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `force overrides an infinite maxAge bar too`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)

        // "Serve anything cached" holds off the ordinary sweep; a deliberate refresh outranks it.
        store.stream("k", maxAge = Duration.INFINITE).test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            store.revalidateActive(force = true)

            assertEquals(DataState.Loading(100), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `force does not override a negative-cache suppression window`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val suppressed = mutableListOf<String>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher {
                calls++
                throw java.io.IOException("endpoint down")
            }
            negativeCache { timeToLive = 30.seconds }
            events(object : AquiferEvents<String> {
                override fun onFetchSuppressed(key: String, error: Throwable, remaining: Duration) {
                    suppressed += key
                }
            })
        }

        store.stream("k").test {
            assertEquals(DataState.Loading(null), awaitItem())
            assertEquals("endpoint down", (awaitItem() as DataState.Failure).error.message)
            assertEquals(1, calls)

            // A dead endpoint is remembered, and one gesture must not become a burst against it.
            store.revalidateActive(force = true)
            settle()
            expectNoEvents()
        }
        assertEquals(1, calls)
        assertEquals(listOf("k"), suppressed)
    }

    @Test
    fun `force refreshes a fresh key on a conditional store, replaying its validator`() = runTest {
        val clock = FakeClock()
        val validators = mutableListOf<String?>()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            // A conditional store still loads each entry under force (to replay validators), so
            // unlike a plain one it cannot reach a refresh via a missing entry. This is the case
            // where the force check itself is load-bearing.
            conditionalFetcher { _, validator ->
                validators += validator
                calls++
                FetchResult.Fresh(calls, validator = "etag-$calls")
            }
            freshness { timeToLive = 1.hours }
        }

        store.stream("k").test {
            assertEquals(DataState.Loading(null), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())

            store.revalidateActive() // fresh for another hour: skipped
            settle()
            expectNoEvents()

            store.revalidateActive(force = true)

            assertEquals(DataState.Loading(1), awaitItem())
            assertEquals(DataState.Content(2, Origin.FETCHER, isStale = false), awaitItem())
        }
        assertEquals(2, calls)
        // The forced refetch was still conditional — the stored validator went back out with it.
        assertEquals(listOf(null, "etag-1"), validators)
    }

    @Test
    fun `a forced sweep on a plain store reads no storage at all`() = runTest {
        var reads = 0
        var batchedReads = 0
        val disk = object : SourceOfTruth<String, Int> {
            override suspend fun read(key: String): PersistedEntry<Int>? {
                reads++
                return null
            }

            override suspend fun readAll(keys: Collection<String>): Map<String, PersistedEntry<Int>> {
                batchedReads++
                return emptyMap()
            }

            override suspend fun write(key: String, entry: PersistedEntry<Int>) = Unit
            override suspend fun delete(key: String) = Unit
            override suspend fun deleteAll() = Unit
        }
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(FakeClock())
            fetcher { 1 } // plain: consults no validator, so nothing needs loading first
            persistence(disk)
        }
        store.put("k", 100)

        store.stream("k").test {
            awaitItem()
            store.evictMemory()
            reads = 0
            batchedReads = 0

            store.revalidateActive(force = true)

            assertEquals(DataState.Loading(100), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())
        }
        // Nothing was judged, and a plain fetcher needs no validator, so the sweep skipped storage.
        assertEquals(0, batchedReads)
        assertEquals(0, reads)
    }
}
