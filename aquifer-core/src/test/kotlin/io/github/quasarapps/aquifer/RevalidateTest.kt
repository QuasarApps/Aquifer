package io.github.quasarapps.aquifer

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RevalidateTest {

    @Test
    fun `revalidate active refreshes stale keys with active streams`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)

        store.stream("k").test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            clock.advanceBy(10.minutes) // The entry is now stale; "connectivity returns".
            store.revalidateActive()

            assertEquals(DataState.Loading(100), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `fresh entries are not refetched`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 10.minutes }
        }
        store.put("k", 100)

        store.stream("k").test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            store.revalidateActive()
            settle()
            expectNoEvents()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `keys without active streams are not refetched`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }

        store.get("k") // One-shot read; no stream stays active.
        assertEquals(1, calls)
        clock.advanceBy(10.minutes)

        store.revalidateActive()
        settle()

        assertEquals(1, calls)
    }

    @Test
    fun `cache only streams do not make a key active`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)
        clock.advanceBy(10.minutes)

        store.stream("k", Freshness.CacheOnly).test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = true), awaitItem())

            store.revalidateActive()
            settle()
            expectNoEvents()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `a cancelled stream no longer keeps its key active`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)

        turbineScope {
            val stream = store.stream("k").testIn(backgroundScope)
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), stream.awaitItem())
            stream.cancelAndIgnoreRemainingEvents()
        }
        settle() // Let the cancellation unwind and unregister the key.

        clock.advanceBy(10.minutes)
        store.revalidateActive()
        settle()

        assertEquals(0, calls)
    }

    @Test
    fun `revalidateOn triggers revalidation on every emission`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        val connectivityRestored = MutableSharedFlow<Unit>()
        store.revalidateOn(connectivityRestored)
        store.put("k", 100)

        store.stream("k").test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            clock.advanceBy(10.minutes)
            connectivityRestored.emit(Unit)

            assertEquals(DataState.Loading(100), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())

            // A second reconnect while everything is fresh does nothing.
            connectivityRestored.emit(Unit)
            settle()
            expectNoEvents()
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a throwing trigger is contained and reported, not crashing the process`() = runTest {
        val failures = mutableListOf<Throwable>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            fetcher { 1 }
            events(object : AquiferEvents<String> {
                override fun onRevalidationTriggerFailed(error: Throwable) {
                    failures += error
                }
            })
        }

        store.revalidateOn(kotlinx.coroutines.flow.flow<Unit> { error("broken trigger") })
        settle()

        // Without containment the exception escapes the supervisor as an uncaught error
        // (which runTest would surface as a test failure). The store keeps working:
        assertEquals(1, store.get("k"))
        assertEquals("broken trigger", failures.single().message)
    }

    @Test
    fun `a failing revalidation sweep does not end the trigger subscription`() = runTest {
        val failures = mutableListOf<Throwable>()
        var failReads = false
        val disk = object : SourceOfTruth<String, Int> {
            override suspend fun read(key: String): PersistedEntry<Int>? =
                if (failReads) throw java.io.IOException("disk hiccup") else null

            override suspend fun write(key: String, entry: PersistedEntry<Int>) = Unit
            override suspend fun delete(key: String) = Unit
            override suspend fun deleteAll() = Unit
        }
        val clock = FakeClock()
        var fetches = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { key -> if (key == "k") ++fetches else -1 }
            freshness { timeToLive = 1.minutes }
            memoryCache { maxEntries = 1 }
            persistence(disk)
            events(object : AquiferEvents<String> {
                override fun onRevalidationTriggerFailed(error: Throwable) {
                    failures += error
                }
            })
        }
        val trigger = MutableSharedFlow<Unit>()
        store.revalidateOn(trigger)

        store.stream("k").test {
            assertEquals(DataState.Loading(null), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())

            store.get("evictor") // pushes "k" out of the size-1 memory cache
            clock.advanceBy(10.minutes) // "k" is stale; the sweep must consult storage

            failReads = true
            trigger.emit(Unit) // this sweep dies on the failing disk read…
            settle()
            assertEquals("disk hiccup", failures.single().message)
            expectNoEvents()

            failReads = false
            trigger.emit(Unit) // …but the subscription survives and the next sweep works.
            assertEquals(DataState.Loading(1), awaitItem())
            assertEquals(DataState.Content(2, Origin.FETCHER, isStale = false), awaitItem())
        }
        assertEquals(2, fetches)
    }

    @Test
    fun `the sweep judges a stream by its own maxAge, not the store TTL`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.hours }
        }
        store.put("k", 100)

        // Still fresh by the store's 1-hour TTL, but well past the 30 seconds this caller asked for.
        store.stream("k", maxAge = 30.seconds).test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            clock.advanceBy(5.minutes)
            store.revalidateActive()

            assertEquals(DataState.Loading(100), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a maxAge stream is swept even under the default infinite TTL`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            // No freshness block: timeToLive defaults to INFINITE, so nothing is ever stale
            // by the store's own reckoning and the sweep used to skip this key forever.
        }
        store.put("k", 100)

        store.stream("k", maxAge = 30.seconds).test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            clock.advanceBy(5.minutes)
            store.revalidateActive()

            assertEquals(DataState.Loading(100), awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), awaitItem())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `an unelapsed maxAge does not make the sweep refresh the key`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)

        // Declaring a bar is not the same as exceeding it: 30s of a 1-hour bar have passed.
        store.stream("k", maxAge = 1.hours).test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            clock.advanceBy(30.seconds)
            store.revalidateActive()
            settle()
            expectNoEvents()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `an infinite maxAge holds the sweep off even past the store TTL`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)

        // "Serve anything cached" is a real answer, and it outranks the store TTL here exactly
        // as it does on the read that declared it.
        store.stream("k", maxAge = Duration.INFINITE).test {
            assertEquals(DataState.Content(100, Origin.MEMORY, isStale = false), awaitItem())

            clock.advanceBy(10.minutes)
            store.revalidateActive()
            settle()
            expectNoEvents()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `a stream without a maxAge is still judged by the store TTL`() = runTest {
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

            clock.advanceBy(5.minutes) // past a tight bar, but nobody asked for one
            store.revalidateActive()
            settle()
            expectNoEvents()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `the tightest bar among several streams of one key wins`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.hours }
        }
        store.put("k", 100)

        turbineScope {
            val relaxed = store.stream("k", maxAge = 1.hours).testIn(backgroundScope)
            val strict = store.stream("k", maxAge = 30.seconds).testIn(backgroundScope)
            relaxed.awaitItem()
            strict.awaitItem()

            clock.advanceBy(5.minutes) // fresh for `relaxed`, stale for `strict`

            store.revalidateActive()

            // One shared fetch, delivered to both collectors.
            assertEquals(DataState.Loading(100), relaxed.awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), relaxed.awaitItem())
            assertEquals(DataState.Loading(100), strict.awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), strict.awaitItem())

            relaxed.cancelAndIgnoreRemainingEvents()
            strict.cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a cancelled stream takes only its own bar with it`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.hours }
        }
        store.put("k", 100)

        turbineScope {
            val relaxed = store.stream("k").testIn(backgroundScope)
            val strict = store.stream("k", maxAge = 30.seconds).testIn(backgroundScope)
            relaxed.awaitItem()
            strict.awaitItem()

            strict.cancelAndIgnoreRemainingEvents()
            settle() // let the cancellation unwind and drop the 30-second bar

            clock.advanceBy(5.minutes)
            store.revalidateActive()
            settle()

            // Only the relaxed collector is left, so the store TTL governs again.
            relaxed.expectNoEvents()
            relaxed.cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `two streams sharing one bar both have to detach before it is dropped`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.hours }
        }
        store.put("k", 100)

        turbineScope {
            val first = store.stream("k", maxAge = 30.seconds).testIn(backgroundScope)
            val second = store.stream("k", maxAge = 30.seconds).testIn(backgroundScope)
            first.awaitItem()
            second.awaitItem()

            first.cancelAndIgnoreRemainingEvents()
            settle() // the bar is held twice; one detaching must not drop it

            clock.advanceBy(5.minutes)
            store.revalidateActive()

            assertEquals(DataState.Loading(100), second.awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), second.awaitItem())
            second.cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, calls)
    }

    @Test
    fun `the sweep resolves every active key in one batched read`() = runTest {
        var reads = 0
        val batches = mutableListOf<Set<String>>()
        val persisted = mutableMapOf<String, PersistedEntry<Int>>()
        val disk = object : SourceOfTruth<String, Int> {
            override suspend fun read(key: String): PersistedEntry<Int>? {
                reads++
                return persisted[key]
            }

            // A real batched store (SQLDelight's `IN`-clause adapter) overrides this; the default
            // would loop over read() and hide the very thing being asserted. Recording the keys of
            // each call, not just a count, is what stops a partial batch from passing.
            override suspend fun readAll(keys: Collection<String>): Map<String, PersistedEntry<Int>> {
                batches += keys.toSet()
                return keys.mapNotNull { key -> persisted[key]?.let { key to it } }.toMap()
            }

            override suspend fun write(key: String, entry: PersistedEntry<Int>) {
                persisted[key] = entry
            }

            override suspend fun delete(key: String) {
                persisted -= key
            }

            override suspend fun deleteAll() = persisted.clear()
        }
        val clock = FakeClock()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { key -> key.last().digitToInt() }
            freshness { timeToLive = 1.minutes }
            persistence(disk)
        }
        val keys = listOf("k1", "k2", "k3", "k4")
        keys.forEach { store.put(it, 0) }

        turbineScope {
            // Fetch-capable streams, so all four keys are active. The entries are still fresh
            // against the 1-minute TTL, so nothing refetches and the only reads are the sweep's.
            val streams = keys.map { store.stream(it).testIn(backgroundScope) }
            streams.forEach { it.awaitItem() }

            // Shed memory so *every* active key is a miss — the cold reconnect this batching is
            // for. Leaving one key resident would let a sweep that batched only a subset pass.
            store.evictMemory()
            reads = 0
            batches.clear()

            store.revalidateActive()
            settle()

            // One batched call, and it carried the whole cold set — not just some of it.
            assertEquals(listOf(keys.toSet()), batches)
            assertEquals(0, reads)

            streams.forEach { it.cancelAndIgnoreRemainingEvents() }
        }
    }

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

    @Test
    fun `multiple streams of one key trigger a single shared refresh`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            fetcher { ++calls }
            freshness { timeToLive = 1.minutes }
        }
        store.put("k", 100)

        turbineScope {
            val first = store.stream("k").testIn(backgroundScope)
            val second = store.stream("k").testIn(backgroundScope)
            first.awaitItem()
            second.awaitItem()

            clock.advanceBy(10.minutes)
            store.revalidateActive()

            assertEquals(DataState.Loading(100), first.awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), first.awaitItem())
            assertEquals(DataState.Loading(100), second.awaitItem())
            assertEquals(DataState.Content(1, Origin.FETCHER, isStale = false), second.awaitItem())

            first.cancelAndIgnoreRemainingEvents()
            second.cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, calls)
    }
}
