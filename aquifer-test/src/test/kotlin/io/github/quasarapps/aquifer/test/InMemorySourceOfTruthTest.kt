package io.github.quasarapps.aquifer.test

import io.github.quasarapps.aquifer.AquiferEvents
import io.github.quasarapps.aquifer.Freshness
import io.github.quasarapps.aquifer.PersistedEntry
import io.github.quasarapps.aquifer.aquifer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class InMemorySourceOfTruthTest {

    private fun store() = InMemorySourceOfTruth<String, Int>()

    @Test
    fun `reading an unknown key returns null`() = runTest {
        assertNull(store().read("nope"))
    }

    @Test
    fun `write then read round-trips the full entry`() = runTest {
        val store = store()
        val entry = PersistedEntry(
            value = 42,
            writtenAtMillis = 100,
            validator = "etag",
            serverFreshForMillis = 60_000L,
        )

        store.write("a", entry)

        assertEquals(entry, store.read("a")) // value, timestamp, validator and freshness horizon all round-trip
    }

    @Test
    fun `writing a key again replaces the previous entry`() = runTest {
        val store = store()
        store.write("a", PersistedEntry(1, writtenAtMillis = 1))
        store.write("a", PersistedEntry(2, writtenAtMillis = 2))

        assertEquals(PersistedEntry(2, writtenAtMillis = 2), store.read("a"))
    }

    @Test
    fun `delete removes one key and deleteAll clears, with entries reflecting each`() = runTest {
        val store = store()
        store.write("a", PersistedEntry(1, writtenAtMillis = 1))
        store.write("b", PersistedEntry(2, writtenAtMillis = 2))

        store.delete("a")
        assertNull(store.read("a"))
        assertEquals(setOf("b"), store.entries.keys)

        store.deleteAll()
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `entries is an insertion-ordered defensive copy`() = runTest {
        val store = store()
        store.write("first", PersistedEntry(1, writtenAtMillis = 1))
        store.write("second", PersistedEntry(2, writtenAtMillis = 2))

        val snapshot = store.entries
        assertEquals(listOf("first", "second"), snapshot.keys.toList()) // write order preserved

        store.write("third", PersistedEntry(3, writtenAtMillis = 3))
        assertEquals(setOf("first", "second"), snapshot.keys) // the earlier snapshot is unaffected
    }

    @Test
    fun `re-writing an existing key keeps its original position - first-write order`() = runTest {
        val store = store()
        store.write("a", PersistedEntry(1, writtenAtMillis = 1))
        store.write("b", PersistedEntry(2, writtenAtMillis = 2))
        store.write("a", PersistedEntry(10, writtenAtMillis = 3)) // re-write, not a fresh key

        // LinkedHashMap does not move a key on re-put, so "a" stays first even though it was written last.
        assertEquals(listOf("a", "b"), store.entries.keys.toList())
        assertEquals(listOf("a", "b"), store.keys().toList())
        assertEquals(10, store.read("a")?.value) // the value is updated in place
    }

    @Test
    fun `readAll returns only the present subset and omits missing keys`() = runTest {
        val store = store()
        store.write("a", PersistedEntry(1, writtenAtMillis = 1))
        store.write("b", PersistedEntry(2, writtenAtMillis = 2))

        assertEquals(
            mapOf("a" to PersistedEntry(1, writtenAtMillis = 1), "b" to PersistedEntry(2, writtenAtMillis = 2)),
            store.readAll(listOf("a", "b", "missing")),
        )
    }

    @Test
    fun `writeAll persists every entry and deleteMany removes the given set`() = runTest {
        val store = store()
        store.writeAll(
            mapOf(
                "a" to PersistedEntry(1, writtenAtMillis = 1),
                "b" to PersistedEntry(2, writtenAtMillis = 2),
                "c" to PersistedEntry(3, writtenAtMillis = 3),
            ),
        )
        assertEquals(setOf("a", "b", "c"), store.entries.keys)

        store.deleteMany(listOf("a", "c", "never-written"))
        assertEquals(setOf("b"), store.entries.keys)
    }

    @Test
    fun `keys enumerates the full set, is empty when empty, and keysWhere filters`() = runTest {
        val store = store()
        assertEquals(emptySet(), store.keys()) // non-null, empty when the store holds nothing

        store.write("tenant:a", PersistedEntry(1, writtenAtMillis = 1))
        store.write("tenant:b", PersistedEntry(2, writtenAtMillis = 2))
        store.write("other:c", PersistedEntry(3, writtenAtMillis = 3))

        assertEquals(setOf("tenant:a", "tenant:b", "other:c"), store.keys())
        assertEquals(setOf("tenant:a", "tenant:b"), store.keysWhere { it.startsWith("tenant:") })
    }

    @OptIn(ExperimentalCoroutinesApi::class) // TestScope.testScheduler.currentTime
    @Test
    fun `latency delays an operation in virtual time`() = runTest {
        val store = store()
        store.latency = 5.seconds

        store.write("a", PersistedEntry(1, writtenAtMillis = 1))

        assertEquals(5.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `latency injected at construction also delays`() = runTest {
        val store = InMemorySourceOfTruth<String, Int>(latency = 3.seconds)

        store.read("a")

        @OptIn(ExperimentalCoroutinesApi::class)
        assertEquals(3.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `failWith makes every operation throw the exact throwable, and clearing it resumes`() = runTest {
        val store = store()
        val boom = IOException("disk full")
        store.failWith = boom

        assertSame(boom, assertFailsWith<IOException> { store.write("a", PersistedEntry(1, writtenAtMillis = 1)) })
        assertSame(boom, assertFailsWith<IOException> { store.read("a") })
        assertSame(boom, assertFailsWith<IOException> { store.keys() })

        store.failWith = null // resume normal operation
        store.write("a", PersistedEntry(1, writtenAtMillis = 1))
        assertEquals(1, store.read("a")?.value)
    }

    @Test
    fun `failWritesWith fails the mutations while reads still succeed`() = runTest {
        val store = store()
        store.write("seeded", PersistedEntry(1, writtenAtMillis = 1)) // before the knob is armed
        val boom = IOException("writes down")
        store.failWritesWith = boom

        // Mutations throw...
        assertSame(boom, assertFailsWith<IOException> { store.write("a", PersistedEntry(2, writtenAtMillis = 2)) })
        assertSame(boom, assertFailsWith<IOException> { store.deleteMany(listOf("seeded")) })
        // ...but reads still hydrate.
        assertEquals(1, store.read("seeded")?.value)
        assertEquals(setOf("seeded"), store.keys())
    }

    @Test
    fun `failReadsWith fails the reads while writes still succeed`() = runTest {
        val store = store()
        val boom = IOException("reads down")
        store.failReadsWith = boom

        // Reads throw...
        assertSame(boom, assertFailsWith<IOException> { store.read("a") })
        assertSame(boom, assertFailsWith<IOException> { store.keys() })
        // ...but a write still lands (verified via a defensive-copy snapshot, which no knob gates).
        store.write("a", PersistedEntry(7, writtenAtMillis = 1))
        assertEquals(7, store.entries["a"]?.value)
    }

    @Test
    fun `a direction-specific knob takes precedence over failWith`() = runTest {
        val store = store()
        val all = IOException("everything down")
        val writes = IOException("only writes down")
        store.failWith = all
        store.failWritesWith = writes

        assertSame(writes, assertFailsWith<IOException> { store.write("a", PersistedEntry(1, writtenAtMillis = 1)) })
        assertSame(all, assertFailsWith<IOException> { store.read("a") }) // reads fall back to failWith
    }

    @Test
    fun `a negative latency is rejected at construction and on assignment`() = runTest {
        assertFailsWith<IllegalArgumentException> { InMemorySourceOfTruth<String, Int>(latency = -(1.seconds)) }
        assertFailsWith<IllegalArgumentException> { store().latency = -(1.seconds) }
    }

    @Test
    fun `latency round-trips, and INFINITE is preserved exactly`() = runTest {
        val store = store()
        store.latency = 5.seconds
        assertEquals(5.seconds, store.latency)

        // INFINITE is tracked by its own bit, so it reads back as INFINITE and is never confused
        // with a large-but-finite value that saturates to the same nanos ceiling.
        store.latency = Duration.INFINITE
        assertEquals(Duration.INFINITE, store.latency)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
    @Test
    fun `a large finite latency completes and never reads back as INFINITE`() = runTest {
        val store = store()
        // ~1000 years is finite but past the ~292-year nanosecond ceiling, so its stored nanos
        // saturate to the same Long.MAX_VALUE that INFINITE would. It must stay finite regardless:
        // read back finite, and — unlike INFINITE's awaitCancellation — actually complete.
        store.latency = (1_000 * 365).days
        assertTrue(store.latency.isFinite(), "a finite latency must not read back as INFINITE")

        // A foreground async so advanceUntilIdle drives the delay to completion (it does not advance
        // background-scope-only work); a true INFINITE would awaitCancellation and never resume.
        val op = async { store.read("k") }
        advanceUntilIdle()
        assertTrue(op.isCompleted, "a finite latency, however large, completes once time reaches it")
    }

    @OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
    @Test
    fun `INFINITE latency parks an operation forever, not for a finite span`() = runTest {
        val store = store()
        store.latency = Duration.INFINITE
        val op = backgroundScope.async { store.read("k") }

        // A finite ~292-year delay would be run by advanceUntilIdle; awaitCancellation has no resume
        // to schedule, so the operation is still suspended afterwards — the "never responds" contract.
        advanceUntilIdle()
        assertTrue(op.isActive, "the operation never completes on its own under INFINITE latency")

        op.cancel()
    }

    // --- Driven through a real Aquifer: the fixture's whole point is exercising the engine's
    // --- persistence paths, so these poke it via the engine rather than as a bare map.

    @Test
    fun `a real Aquifer hydrates a memory miss from the fixture`() = runTest {
        val disk = InMemorySourceOfTruth<String, Int>()
        disk.write("k", PersistedEntry(value = 7, writtenAtMillis = 0))
        var fetches = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(FakeClock())
            fetcher {
                fetches++
                -1
            }
            persistence(disk)
        }

        // CacheOnly never fetches, so the only path to a value is hydration from persistence.
        assertEquals(7, store.get("k", Freshness.CacheOnly))
        assertEquals(0, fetches, "the value came from the fixture, not the fetcher")
    }

    @Test
    fun `a real Aquifer writes a fetched value through to the fixture`() = runTest {
        val disk = InMemorySourceOfTruth<String, Int>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(FakeClock())
            fetcher { it.length }
            persistence(disk)
        }

        assertEquals(3, store.get("abc"))
        settle() // the write-through lands on the store's scope
        assertEquals(setOf("abc"), disk.entries.keys)
        assertEquals(3, disk.read("abc")?.value)
    }

    @Test
    fun `an enumerable fixture lets invalidateWhere reach a disk-only key`() = runTest {
        val disk = InMemorySourceOfTruth<String, Int>()
        // A key only persistence knows — never loaded into memory this run.
        disk.write("tenant:gone", PersistedEntry(value = 1, writtenAtMillis = 0))
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(FakeClock())
            fetcher { it.length }
            persistence(disk)
        }

        store.invalidateWhere { it.startsWith("tenant:") }
        settle()

        // keysWhere let the sweep enumerate persistence and drop the disk-only match.
        assertNull(disk.read("tenant:gone"))
    }

    @Test
    fun `failWritesWith surfaces onPersistenceWriteFailed while reads still hydrate`() = runTest {
        val disk = InMemorySourceOfTruth<String, Int>()
        disk.write("cached", PersistedEntry(value = 1, writtenAtMillis = 0))
        disk.failWritesWith = IOException("disk full")
        val writeFailures = mutableListOf<String>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(FakeClock())
            fetcher { it.length }
            persistence(disk)
            events(object : AquiferEvents<String> {
                override fun onPersistenceWriteFailed(key: String, error: Throwable) {
                    writeFailures += key
                }
            })
        }

        // Reads still hydrate despite failing writes...
        assertEquals(1, store.get("cached", Freshness.CacheOnly))
        // ...while a fetched value's best-effort write-through fails and is reported, not thrown.
        assertEquals(5, store.get("fresh")) // "fresh".length, fetched then written through
        settle()
        assertEquals(listOf("fresh"), writeFailures)
    }
}
