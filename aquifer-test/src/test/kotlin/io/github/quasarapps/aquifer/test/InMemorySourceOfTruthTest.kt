package io.github.quasarapps.aquifer.test

import io.github.quasarapps.aquifer.PersistedEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
    fun `a negative latency is rejected at construction and on assignment`() = runTest {
        assertFailsWith<IllegalArgumentException> { InMemorySourceOfTruth<String, Int>(latency = -(1.seconds)) }
        assertFailsWith<IllegalArgumentException> { store().latency = -(1.seconds) }
    }
}
