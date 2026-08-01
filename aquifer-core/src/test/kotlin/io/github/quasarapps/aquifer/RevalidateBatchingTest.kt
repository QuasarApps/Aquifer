package io.github.quasarapps.aquifer

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How a sweep talks to storage and the network: one batched read, and one batched fetch. */
class RevalidateBatchingTest {

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
    fun `the sweep fetches every stale key in one batch call`() = runTest {
        val clock = FakeClock()
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            batchFetcher { keys ->
                batches += keys
                keys.associateWith { it.last().digitToInt() }
            }
            freshness { timeToLive = 1.minutes }
        }
        val keys = listOf("k1", "k2", "k3", "k4")
        keys.forEach { store.put(it, 0) }

        turbineScope {
            val streams = keys.map { store.stream(it).testIn(backgroundScope) }
            streams.forEach { it.awaitItem() }

            clock.advanceBy(10.minutes) // every key is stale; "connectivity returns"
            batches.clear()
            store.revalidateActive()
            settle()

            // One round-trip for the whole screen, not one per key.
            assertEquals(listOf(keys.toSet()), batches)
            streams.forEach { it.cancelAndIgnoreRemainingEvents() }
        }
    }

    @Test
    fun `the sweep still fetches per key when no batch fetcher is configured`() = runTest {
        val clock = FakeClock()
        val fetched = mutableListOf<String>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            // A single-key fetcher has no multi-key transport, so batching is not available;
            // the sweep must still refresh each key independently rather than dropping any.
            fetcher { key ->
                fetched += key
                key.last().digitToInt()
            }
            freshness { timeToLive = 1.minutes }
        }
        val keys = listOf("k1", "k2", "k3")
        keys.forEach { store.put(it, 0) }

        turbineScope {
            val streams = keys.map { store.stream(it).testIn(backgroundScope) }
            streams.forEach { it.awaitItem() }

            clock.advanceBy(10.minutes)
            fetched.clear()
            store.revalidateActive()
            settle()

            assertEquals(keys.toSet(), fetched.toSet())
            streams.forEach { it.cancelAndIgnoreRemainingEvents() }
        }
    }

    @Test
    fun `a suppressed key is left out of the sweep's batch`() = runTest {
        val clock = FakeClock()
        val batches = mutableListOf<Set<String>>()
        var failNext = setOf("k2")
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            batchFetcher { keys ->
                batches += keys
                keys.filterNot { it in failNext }.associateWith { it.last().digitToInt() }
            }
            // The suppression window has to outlast the advance below, or k2 would simply be
            // eligible again and the test would prove nothing. (It also has to stay within
            // negativeCache.maxTimeToLive, which defaults to 5 minutes.)
            negativeCache { timeToLive = 2.minutes }
            freshness { timeToLive = 30.seconds }
        }
        val keys = listOf("k1", "k2", "k3")

        turbineScope {
            val streams = keys.map { store.stream(it).testIn(backgroundScope) }
            streams.forEach { it.awaitItem() } // Loading
            settle()

            // k2's fetch failed (the batch omitted it), so it now carries a failure record.
            clock.advanceBy(1.minutes) // past the 30-second entry TTL, inside k2's 2-minute window
            batches.clear()
            store.revalidateActive()
            settle()

            // The whole screen is stale, but the suppressed key is excluded from the call.
            assertEquals(listOf(setOf("k1", "k3")), batches)
            streams.forEach { it.cancelAndIgnoreRemainingEvents() }
        }
    }
}
