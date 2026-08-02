package io.github.quasarapps.aquifer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The residual-hydration guard re-reads persisted state under `commitGuard` when a *commit* raced
 * the off-lock read. These pin that it fires when it must and stays quiet when it must not — the
 * distinction being that hydrating an entry is not a commit.
 */
class HydrationGuardTest {

    /** A source of truth whose reads can be held open, so two cold reads can be interleaved. */
    private class GatedDisk(
        private val entries: Map<String, PersistedEntry<Int>>,
    ) : SourceOfTruth<String, Int> {
        val reads = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun read(key: String): PersistedEntry<Int>? {
            reads += key
            gate?.await()
            return entries[key]
        }

        override suspend fun write(key: String, entry: PersistedEntry<Int>) = Unit
        override suspend fun delete(key: String) = Unit
        override suspend fun deleteAll() = Unit
    }

    @Test
    fun `concurrent cold reads of different keys do not re-read each other's entries`() = runTest {
        val disk = GatedDisk(mapOf("a" to PersistedEntry(1, 0), "b" to PersistedEntry(2, 0)))
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(FakeClock())
            fetcher { error("nothing should reach the network; both keys are on disk") }
            persistence(disk)
        }

        // Hold both off-lock reads open so each captures its generation before either hydrates.
        val gate = CompletableDeferred<Unit>()
        disk.gate = gate
        val reads = listOf(
            async { store.get("a", Freshness.CacheFirst) },
            async { store.get("b", Freshness.CacheFirst) },
        )
        settle()
        assertEquals(listOf("a", "b"), disk.reads) // both are past the pre-lock read

        disk.gate = null
        gate.complete(Unit)
        assertEquals(listOf(1, 2), reads.awaitAll())

        // One read per key. Keyed on the sequencer, the first hydration advanced it and forced the
        // second contender through a guarded re-read of its own key — N concurrent cold reads cost
        // N-1 extra reads, each taken while holding the commit lock.
        assertEquals(listOf("a", "b"), disk.reads)
    }

    @Test
    fun `a racing fetch commit still forces the guarded re-read`() = runTest {
        val disk = GatedDisk(mapOf("a" to PersistedEntry(1, 0)))
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(FakeClock())
            fetcher { 42 }
            persistence(disk)
        }

        val gate = CompletableDeferred<Unit>()
        disk.gate = gate
        val read = async { store.get("a", Freshness.CacheFirst) }
        settle()
        assertEquals(listOf("a"), disk.reads) // past the pre-lock read, not yet holding the lock

        // A *fetch* commit lands in that window. Unlike put/invalidate it does not move the epoch,
        // so the epoch check cannot see it and only the commit counter can — which is the entire
        // reason this second guard exists.
        store.fresh("a")
        // ...and is then shed from memory, so the pending load's memory re-check comes up empty
        // and would otherwise hydrate the stale pre-lock snapshot over the fresher commit.
        store.evictMemory()

        disk.gate = null
        gate.complete(Unit)
        read.await()

        assertEquals(listOf("a", "a"), disk.reads) // the guard fired: the entry was re-read
    }
}
