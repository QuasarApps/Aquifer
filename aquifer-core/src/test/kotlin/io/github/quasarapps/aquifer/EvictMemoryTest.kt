package io.github.quasarapps.aquifer

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `evictMemory()` / `trimToSize(n)`: non-suspending, silent, memory-only shedding for Android's
 * `onTrimMemory`/`onLowMemory`. Persistence is untouched, so dropped keys rehydrate from disk on
 * their next read; the shed disturbs no streams, fences no fetches, and does not count as an LRU
 * eviction. The crown-jewel case is [`evictMemory racing a mid-persist commit`][R0], which pins the
 * `commitFetched` persist-before-bump ordering that keeps the residual-hydration guard honest once
 * an eviction can drop a just-committed entry.
 */
class EvictMemoryTest {

    @Test
    fun `evictMemory drops memory but a later read rehydrates from disk`() = runTest {
        val disk = InMemorySourceOfTruth<String, String>()
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "network-$it" }
            persistence(disk)
        }
        store.put("k", "v")
        assertEquals(setOf("k"), store.snapshot())

        store.evictMemory()

        assertEquals(emptySet(), store.snapshot(), "memory is shed immediately")
        assertEquals(setOf("k"), disk.storage.keys, "persistence is untouched")
        assertEquals("v", disk.storage["k"]?.value)
        // Rehydrates from disk with no fetch.
        assertEquals("v", store.get("k", Freshness.CacheOnly))
        assertEquals(setOf("k"), store.snapshot())
    }

    @Test
    fun `evictMemory with no persistence discards the data`() = runTest {
        var fetches = 0
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher {
                fetches++
                "network-$it"
            }
        }
        store.put("k", "v")

        store.evictMemory()

        assertEquals(emptySet(), store.snapshot())
        assertFailsWith<CacheMissException> { store.get("k", Freshness.CacheOnly) }
        assertEquals("network-k", store.get("k", Freshness.CacheFirst)) // must re-fetch
        assertEquals(1, fetches)
    }

    @Test
    fun `evictMemory on an empty cache is a silent no-op`() = runTest {
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "network-$it" }
        }
        store.evictMemory()
        assertEquals(emptySet(), store.snapshot())
        assertEquals(CacheStats(hits = 0, misses = 0, evictions = 0, inFlight = 0), store.stats())
    }

    @Test
    fun `trimToSize keeps the most-recently-used and drops the least-recently-used`() = runTest {
        val disk = InMemorySourceOfTruth<String, String>()
        val store = aquifer<String, String> {
            scope(backgroundScope)
            memoryCache { maxEntries = 3 }
            fetcher { "network-$it" }
            persistence(disk)
        }
        store.put("a", "1")
        store.put("b", "2")
        store.put("c", "3") // access order (LRU->MRU): a, b, c
        store.get("a", Freshness.CacheOnly) // promote a: b, c, a

        store.trimToSize(2) // drop the single LRU (b), keep the two MRU (c, a)

        assertEquals(setOf("a", "c"), store.snapshot())
        // The dropped key is still on disk and rehydrates on its next read.
        assertEquals("2", store.get("b", Freshness.CacheOnly))
    }

    @Test
    fun `trimToSize(0) empties the cache like evictMemory`() = runTest {
        val disk = InMemorySourceOfTruth<String, String>()
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "network-$it" }
            persistence(disk)
        }
        store.put("a", "1")
        store.put("b", "2")

        store.trimToSize(0)

        assertEquals(emptySet(), store.snapshot())
        assertEquals("1", store.get("a", Freshness.CacheOnly)) // rehydrates
    }

    @Test
    fun `trimToSize at or above the entry count is a no-op`() = runTest {
        val store = aquifer<String, String> {
            scope(backgroundScope)
            memoryCache { maxEntries = 5 }
            fetcher { "network-$it" }
        }
        store.put("a", "1")
        store.put("b", "2")

        store.trimToSize(5)

        assertEquals(setOf("a", "b"), store.snapshot())
    }

    @Test
    fun `trimToSize rejects a negative size and leaves the cache untouched`() = runTest {
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "network-$it" }
        }
        store.put("a", "1")

        assertFailsWith<IllegalArgumentException> { store.trimToSize(-1) }
        assertEquals(setOf("a"), store.snapshot(), "a rejected trim must not shed anything")
    }

    @Test
    fun `evictMemory and trimToSize are safe on a closed store`() = runTest {
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "network-$it" }
        }
        store.put("a", "1")
        store.put("b", "2")
        store.close()

        store.trimToSize(1) // does not throw, unlike suspending members
        store.evictMemory()

        assertEquals(emptySet(), store.snapshot())
    }

    @Test
    fun `manual shedding does not count toward stats evictions`() = runTest {
        val store = aquifer<String, String> {
            scope(backgroundScope)
            memoryCache { maxEntries = 2 }
            fetcher { "network-$it" }
        }
        store.put("a", "1")
        store.put("b", "2")

        store.evictMemory()
        store.trimToSize(0)

        assertEquals(0L, store.stats().evictions, "onTrimMemory shedding is not an LRU eviction")

        // A genuine capacity overflow still increments evictions normally.
        store.put("x", "1")
        store.put("y", "2")
        store.put("z", "3") // overflows maxEntries=2 -> one LRU eviction
        assertEquals(1L, store.stats().evictions)
    }

    @Test
    fun `an active stream is undisturbed by evictMemory`() = runTest {
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "network-$it" }
        }
        store.put("k", "v")

        store.stream("k", Freshness.CacheOnly).test {
            assertEquals(DataState.Content("v", Origin.MEMORY, isStale = false), awaitItem())

            store.evictMemory() // silent: no event, no state change for the live collector

            expectNoEvents()

            store.put("k", "v2") // a real update still flows
            assertEquals(DataState.Content("v2", Origin.LOCAL, isStale = false), awaitItem())
        }
    }

    @Test
    fun `a fetch committed before evictMemory rehydrates its value`() = runTest {
        val disk = InMemorySourceOfTruth<String, String>()
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "network-$it" }
            persistence(disk)
        }
        assertEquals("network-k", store.fresh("k")) // commit lands in memory + disk

        store.evictMemory()

        assertEquals("network-k", store.get("k", Freshness.CacheOnly)) // rehydrated from disk
    }

    /** Capture-then-suspend on the *write*: the first write of "k" resolves its arg, waits, then stores. */
    private class GatedWriteDisk : SourceOfTruth<String, String> {
        val storage = mutableMapOf<String, PersistedEntry<String>>("k" to PersistedEntry("OLD", 0L))
        val writeGate = CompletableDeferred<Unit>()
        private var gated = false

        override suspend fun read(key: String): PersistedEntry<String>? = storage[key]

        override suspend fun write(key: String, entry: PersistedEntry<String>) {
            if (key == "k" && !gated) {
                gated = true
                writeGate.await() // disk stays "OLD" for the duration of this suspended write
            }
            storage[key] = entry
        }

        override suspend fun delete(key: String) {
            storage.remove(key)
        }

        override suspend fun deleteAll() = storage.clear()
    }

    /**
     * R0 — the crown-jewel regression for the `commitFetched` persist-before-bump reorder.
     *
     * A fetch commits "NEW" while its disk write is suspended (sequencer bump/memory write pending);
     * `evictMemory()` drops any resident entry; a concurrent read then hydrates. Before the reorder
     * the commit bumped the sequencer before persisting, so the read would trust its stale "OLD"
     * pre-lock snapshot. This test fails without the reorder.
     */
    @Test
    fun `evictMemory racing a mid-persist commit does not serve stale`() = runTest {
        val disk = GatedWriteDisk()
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "NEW" }
            persistence(disk)
        }

        // A fetch commits: it enters commitFetched and suspends inside the gated disk write.
        val committer = async { store.fresh("k") }
        settle()

        // Memory-pressure shed drops any just-committed entry while the disk write is still pending.
        store.evictMemory()

        // A cache read races in: misses memory, snapshots disk ("OLD"), then blocks on the commit lock.
        val reader = async { store.get("k", Freshness.CacheOnly) }
        settle()

        disk.writeGate.complete(Unit) // the commit's disk write finishes ("NEW"), the lock releases
        settle()

        assertEquals("NEW", committer.await())
        assertEquals("NEW", reader.await(), "the read must not serve the stale pre-commit snapshot")
        assertEquals("NEW", store.get("k", Freshness.CacheOnly))
    }

    @Test
    fun `a fresh stream after evictMemory rehydrates from disk`() = runTest {
        val disk = InMemorySourceOfTruth<String, String>()
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "network-$it" }
            persistence(disk)
        }
        store.put("k", "v")
        store.evictMemory()

        // A brand-new collector subscribing after the shed rehydrates from disk (no fetch), and
        // reports the true PERSISTENCE origin — it must not resurrect a stale value or miss.
        store.stream("k", Freshness.CacheOnly).test {
            assertEquals(DataState.Content("v", Origin.PERSISTENCE, isStale = false), awaitItem())
        }
    }

    @Test
    fun `getAll racing a mid-persist commit does not serve stale`() = runTest {
        // The loadAll twin of the R0 regression: the batch read path must also honor the reorder.
        val disk = GatedWriteDisk()
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher { "NEW" }
            persistence(disk)
        }

        val committer = async { store.fresh("k") }
        settle()

        store.evictMemory()

        val reader = async { store.getAll(setOf("k"), Freshness.CacheOnly) }
        settle()

        disk.writeGate.complete(Unit)
        settle()

        assertEquals("NEW", committer.await())
        assertEquals(mapOf("k" to "NEW"), reader.await(), "getAll must not serve the stale snapshot")
    }

    @Test
    fun `trimToSize(0) with no persistence discards the data`() = runTest {
        var fetches = 0
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher {
                fetches++
                "network-$it"
            }
        }
        store.put("k", "v")

        store.trimToSize(0)

        assertFailsWith<CacheMissException> { store.get("k", Freshness.CacheOnly) }
        assertEquals("network-k", store.get("k", Freshness.CacheFirst))
        assertEquals(1, fetches)
    }

    /** Capture-then-suspend fetcher: the first fetch of "k" waits at the gate before resolving. */
    @Test
    fun `evictMemory does not fence an in-flight fetch`() = runTest {
        val disk = InMemorySourceOfTruth<String, String>()
        val fetchGate = CompletableDeferred<Unit>()
        val store = aquifer<String, String> {
            scope(backgroundScope)
            fetcher {
                fetchGate.await()
                "network-$it"
            }
            persistence(disk)
        }

        val pending = async { store.fresh("k") } // fetch starts, waits at the gate
        settle()

        store.evictMemory() // a shed must NOT fence the fetch (contrast invalidate, which does)

        fetchGate.complete(Unit)
        assertEquals("network-k", pending.await())
        // The commit was not fenced: the value is cached and persisted.
        assertEquals("network-k", store.get("k", Freshness.CacheOnly))
        assertEquals("network-k", disk.storage["k"]?.value)
    }
}
