package io.github.quasarapps.aquifer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The residual hydration race (issue #13's thread): [load]/`loadAll` read persistence *outside*
 * `commitGuard` and, under the lock, re-check only memory before hydrating. A fetch commit does not
 * move the epoch, so that memory re-check is the sole guard against a stale pre-lock snapshot
 * overwriting a fresher commit — and it fails the moment the committed entry is evicted before the
 * read resumes. `MutationFencingTest` covers the non-evicted case (the memory re-check catches it);
 * these tests force the eviction with a one-slot memory cache and assert the fresher value still
 * wins, exercising the re-read guard keyed on `commitGen` — the commit-only generation counter,
 * which hydration deliberately does not advance (see `HydrationGuardTest` for that half).
 */
class ResidualHydrationRaceTest {

    /**
     * Capture-then-suspend read that gates only the *first* read of [gatedKey] — modelling a slow
     * storage read that started before a racing commit — while later reads (the guard's under-lock
     * re-read) pass through with the current value.
     */
    private class GatedDisk(private val gatedKey: String) : SourceOfTruth<String, String> {
        val storage = mutableMapOf<String, PersistedEntry<String>>()
        val gate = CompletableDeferred<Unit>()
        private var gated = false

        override suspend fun read(key: String): PersistedEntry<String>? {
            if (key == gatedKey && !gated) {
                gated = true
                val snapshot = storage[key] // the pre-commit snapshot, captured before suspending
                gate.await()
                return snapshot
            }
            return storage[key]
        }

        override suspend fun write(key: String, entry: PersistedEntry<String>) {
            storage[key] = entry
        }

        override suspend fun delete(key: String) {
            storage.remove(key)
        }

        override suspend fun deleteAll() = storage.clear()
    }

    @Test
    fun `load does not hydrate a stale snapshot over a fresher commit that was evicted`() = runTest {
        val disk = GatedDisk("A").apply { storage["A"] = PersistedEntry("old-A", 0L) }
        val store = aquifer<String, String> {
            scope(backgroundScope)
            memoryCache { maxEntries = 1 } // one slot: committing another key evicts A
            fetcher { key -> "network-$key" }
            persistence(disk)
        }

        // A cache-only read of A misses memory and suspends inside the gated disk read, holding the
        // pre-commit snapshot ("old-A").
        val reader = async { store.get("A", Freshness.CacheOnly) }
        settle()

        // A fetch commits the fresher "network-A" to memory and disk while the read is suspended…
        assertEquals("network-A", store.fresh("A"))
        // …then a fetch for B evicts A from the one-slot memory cache: the surviving-commit trace is
        // gone from memory, though "network-A" is now on disk.
        assertEquals("network-B", store.fresh("B"))

        disk.gate.complete(Unit) // the suspended read resumes with its stale "old-A" snapshot
        settle()

        // The resumed hydration must re-read the current disk value rather than put "old-A" back
        // over the fresher (evicted) "network-A".
        assertEquals("network-A", reader.await())
        assertEquals("network-A", store.get("A", Freshness.CacheOnly))
    }

    @Test
    fun `loadAll does not hydrate a stale snapshot over a fresher commit that was evicted`() = runTest {
        val disk = GatedDisk("A").apply { storage["A"] = PersistedEntry("old-A", 0L) }
        val store = aquifer<String, String> {
            scope(backgroundScope)
            memoryCache { maxEntries = 1 }
            fetcher { key -> "network-$key" }
            persistence(disk)
        }

        // getAll(CacheOnly) routes through loadAll; its batched read suspends in the gated disk.
        val reader = async { store.getAll(setOf("A"), Freshness.CacheOnly) }
        settle()

        assertEquals("network-A", store.fresh("A"))
        assertEquals("network-B", store.fresh("B"))

        disk.gate.complete(Unit)
        settle()

        assertEquals(mapOf("A" to "network-A"), reader.await())
        assertEquals("network-A", store.get("A", Freshness.CacheOnly))
    }
}
