package io.github.quasarapps.aquifer.internal

import io.github.quasarapps.aquifer.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reconciliation matrix for a new stream collector's initial snapshot: hydrated disk
 * snapshots must survive pure LRU eviction but never survive invalidation or a manual memory shed
 * that raced the subscription, and origins must be reported truthfully.
 */
class SnapshotReconciliationTest {

    private fun entry(sequence: Long) = MemoryCache.Entry("value", writtenAtMillis = 0L, sequence = sequence)

    @Test
    fun `memory hit matching the hydrated commit keeps the persistence origin`() {
        val preloaded = RealAquifer.Snapshot(entry(sequence = 7), Origin.PERSISTENCE)

        val resolved = RealAquifer.reconcileSnapshot(
            preloaded,
            entry(sequence = 7),
            epochUnchanged = true,
            manualEvictRaced = false,
        )

        assertEquals(preloaded, resolved)
    }

    @Test
    fun `a newer memory commit outranks the hydrated snapshot`() {
        val preloaded = RealAquifer.Snapshot(entry(sequence = 7), Origin.PERSISTENCE)
        val newer = entry(sequence = 8)

        val resolved = RealAquifer.reconcileSnapshot(
            preloaded,
            newer,
            epochUnchanged = true,
            manualEvictRaced = false,
        )

        assertEquals(RealAquifer.Snapshot(newer, Origin.MEMORY), resolved)
    }

    @Test
    fun `LRU eviction between hydration and subscription keeps the snapshot`() {
        val preloaded = RealAquifer.Snapshot(entry(sequence = 7), Origin.PERSISTENCE)

        // Memory empty, epoch unmoved, no manual shed: the gap was LRU pressure — the hydrated
        // snapshot still stands (this is the streaming hot path an evictMemory must not disturb).
        val resolved = RealAquifer.reconcileSnapshot(
            preloaded,
            inMemory = null,
            epochUnchanged = true,
            manualEvictRaced = false,
        )

        assertEquals(preloaded, resolved)
    }

    @Test
    fun `a manual shed racing the subscription drops the empty-memory snapshot`() {
        val preloaded = RealAquifer.Snapshot(entry(sequence = 7), Origin.PERSISTENCE)

        // Memory empty and a manual evictMemory/trimToSize raced: it may have dropped a fetch commit
        // this collector missed on the bus, so the hydrated snapshot can no longer be trusted.
        val resolved = RealAquifer.reconcileSnapshot(
            preloaded,
            inMemory = null,
            epochUnchanged = true,
            manualEvictRaced = true,
        )

        assertNull(resolved)
    }

    @Test
    fun `a manual shed does not override a corroborating memory hit`() {
        val preloaded = RealAquifer.Snapshot(entry(sequence = 7), Origin.PERSISTENCE)
        val newer = entry(sequence = 8)

        // A racing shed is irrelevant when memory still has an entry to reconcile against.
        val resolved = RealAquifer.reconcileSnapshot(
            preloaded,
            newer,
            epochUnchanged = true,
            manualEvictRaced = true,
        )

        assertEquals(RealAquifer.Snapshot(newer, Origin.MEMORY), resolved)
    }

    @Test
    fun `invalidation between hydration and subscription drops the snapshot`() {
        val preloaded = RealAquifer.Snapshot(entry(sequence = 7), Origin.PERSISTENCE)

        val resolved = RealAquifer.reconcileSnapshot(
            preloaded,
            inMemory = null,
            epochUnchanged = false,
            manualEvictRaced = false,
        )

        assertNull(resolved)
    }

    @Test
    fun `nothing hydrated and nothing in memory resolves to no snapshot`() {
        assertNull(
            RealAquifer.reconcileSnapshot<String>(
                null,
                inMemory = null,
                epochUnchanged = true,
                manualEvictRaced = false,
            ),
        )
    }
}
