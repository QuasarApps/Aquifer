package io.github.quasarapps.aquifer.test

import io.github.quasarapps.aquifer.PersistedEntry
import io.github.quasarapps.aquifer.SourceOfTruth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An executable version of the [SourceOfTruth] contract: subclass it, point [createStore] at your
 * store, and the clauses that document says in prose become tests that fail when your store
 * disagrees with them.
 *
 * The SPI's contract is easy to read and easy to half-implement. `read` must return `null` for an
 * entry it can no longer decode rather than throw; `readAll` must *omit* a missing key rather than
 * map it to `null`; `keys()` must distinguish "I hold nothing" (an empty set) from "I cannot
 * enumerate" (`null`); every method must tolerate concurrent calls. None of that is checked by the
 * compiler, and a store that gets any of it subtly wrong fails in the engine rather than in
 * itself — a `null` where an omission belonged surfaces as a phantom cache hit, and an empty set
 * returned as `null` silently narrows `Aquifer.invalidateWhere` from disk-wide to in-process.
 *
 * ```
 * class MyStoreContractTest : AbstractSourceOfTruthContractTest() {
 *     @TempDir lateinit var dir: Path
 *     override val isEnumerable: Boolean = false
 *     override fun createStore(): SourceOfTruth<String, String> = MyStore(dir)
 * }
 * ```
 *
 * ### What it does and does not pin
 *
 * Every test builds a **fresh, empty** store through [createStore] and releases it through
 * [destroyStore], so tests never observe each other's writes. The suite fixes the key and value
 * types at `String` deliberately: the contract says nothing about serialization, so a store that
 * only works for its own value type is out of scope, and concrete types keep the failure messages
 * readable.
 *
 * It pins the clauses the SPI states as requirements. It does **not** pin behaviour the SPI leaves
 * to the implementation — notably that [SourceOfTruth.writeAll] and [SourceOfTruth.deleteMany] are
 * *permitted* to be non-atomic, so neither an all-or-nothing store nor a partial-prefix one is
 * failed here. Where a clause is genuinely optional the suite exposes a hook rather than guessing:
 * [isEnumerable], [persistsValidator], [persistsServerFreshFor] and [writeUndecodableEntry].
 */
public abstract class AbstractSourceOfTruthContractTest {

    /**
     * Returns a fresh store holding no entries. Called once per test, so it must not hand back a
     * store a previous test already wrote to — give each call its own directory, database or map.
     */
    protected abstract fun createStore(): SourceOfTruth<String, String>

    /**
     * Releases whatever [createStore] allocated (a database connection, a file handle). Runs after
     * each test even when it fails. The default does nothing, which suits a store whose resources
     * are reclaimed with the temporary directory the test framework already cleans up.
     */
    protected open fun destroyStore(store: SourceOfTruth<String, String>) {}

    /**
     * Whether the store under test opts into key enumeration — that is, whether it overrides
     * [SourceOfTruth.keys] to return non-`null`. Declaring this wrongly is itself caught: the
     * enumeration tests assert the opted-out store returns `null` just as firmly as they assert the
     * enumerable one returns a set.
     */
    protected abstract val isEnumerable: Boolean

    /**
     * Whether [PersistedEntry.validator] survives a write/read round trip. `true` by default, since
     * dropping it silently turns every conditional refetch into a full download. Override to
     * `false` only for a store that deliberately does not carry the token.
     */
    protected open val persistsValidator: Boolean get() = true

    /**
     * Whether [PersistedEntry.serverFreshForMillis] survives a write/read round trip. `true` by
     * default; dropping it makes the origin's freshness horizon evaporate across a restart, falling
     * back to the store's own TTL.
     */
    protected open val persistsServerFreshFor: Boolean get() = true

    /**
     * Writes an entry under [key] that this store will later be unable to decode, so the suite can
     * check it reads back as *absent* rather than throwing. Return `false` — the default — when the
     * store has no way to get into that state, and the two tests that need it are skipped rather
     * than passing vacuously.
     */
    protected open suspend fun writeUndecodableEntry(
        store: SourceOfTruth<String, String>,
        key: String,
    ): Boolean = false

    /** Builds an entry; the defaults keep the call sites in the tests below short. */
    protected fun entry(
        value: String,
        writtenAtMillis: Long = 0L,
        validator: String? = null,
        serverFreshForMillis: Long? = null,
    ): PersistedEntry<String> = PersistedEntry(value, writtenAtMillis, validator, serverFreshForMillis)

    /** Runs [block] against a fresh store, releasing it even when the assertions fail. */
    private fun withStore(block: suspend (SourceOfTruth<String, String>) -> Unit) {
        val store = createStore()
        try {
            runBlocking { block(store) }
        } finally {
            destroyStore(store)
        }
    }

    // ---------------------------------------------------------------- single-key reads and writes

    @Test
    public fun `read returns null for a key that was never written`() = withStore { store ->
        assertNull(store.read("absent"))
    }

    @Test
    public fun `a written entry reads back with its value and write timestamp`() = withStore { store ->
        store.write("k", entry("v", writtenAtMillis = 1_234))

        val read = assertNotNull(store.read("k"))
        assertEquals("v", read.value)
        assertEquals(1_234, read.writtenAtMillis, "the write timestamp is what keeps TTL correct across restarts")
    }

    @Test
    public fun `a write replaces the previous entry for that key`() = withStore { store ->
        store.write("k", entry("first", writtenAtMillis = 1))
        store.write("k", entry("second", writtenAtMillis = 2))

        val read = assertNotNull(store.read("k"))
        assertEquals("second", read.value)
        assertEquals(2, read.writtenAtMillis)
    }

    @Test
    public fun `the validator survives a round trip`() = withStore { store ->
        assumeTrue(persistsValidator, "store declares it does not persist the validator")
        store.write("k", entry("v", validator = "W/\"etag-1\""))

        assertEquals("W/\"etag-1\"", assertNotNull(store.read("k")).validator)
    }

    @Test
    public fun `the server freshness horizon survives a round trip`() = withStore { store ->
        assumeTrue(persistsServerFreshFor, "store declares it does not persist serverFreshForMillis")
        store.write("k", entry("v", serverFreshForMillis = 30_000))

        assertEquals(30_000, assertNotNull(store.read("k")).serverFreshForMillis)
    }

    @Test
    public fun `absent optional envelope fields read back as null`() = withStore { store ->
        store.write("k", entry("v"))

        val read = assertNotNull(store.read("k"))
        assertNull(read.validator)
        assertNull(read.serverFreshForMillis)
    }

    // ------------------------------------------------------------------------------ undecodable

    @Test
    public fun `an entry the store cannot decode reads as absent rather than throwing`() = withStore { store ->
        assumeTrue(writeUndecodableEntry(store, "bad"), "store cannot produce an undecodable entry")

        assertNull(store.read("bad"), "the contract says treat an undecodable entry as absent, not as an error")
    }

    @Test
    public fun `readAll omits an entry the store cannot decode`() = withStore { store ->
        assumeTrue(writeUndecodableEntry(store, "bad"), "store cannot produce an undecodable entry")
        store.write("good", entry("v"))

        assertEquals(setOf("good"), store.readAll(listOf("bad", "good")).keys)
    }

    // ------------------------------------------------------------------------------------ delete

    @Test
    public fun `delete removes the entry`() = withStore { store ->
        store.write("k", entry("v"))

        store.delete("k")

        assertNull(store.read("k"))
    }

    @Test
    public fun `deleting a key that has no entry is not an error`() = withStore { store ->
        store.delete("never-written")

        assertNull(store.read("never-written"))
    }

    @Test
    public fun `delete leaves the other keys intact`() = withStore { store ->
        store.write("a", entry("1"))
        store.write("b", entry("2"))

        store.delete("a")

        assertNull(store.read("a"))
        assertEquals("2", assertNotNull(store.read("b")).value)
    }

    @Test
    public fun `deleteAll removes every entry`() = withStore { store ->
        store.write("a", entry("1"))
        store.write("b", entry("2"))

        store.deleteAll()

        assertNull(store.read("a"))
        assertNull(store.read("b"))
    }

    @Test
    public fun `deleteAll on an empty store is not an error`() = withStore { store ->
        store.deleteAll()

        assertNull(store.read("anything"))
    }

    // ----------------------------------------------------------------------------------- readAll

    @Test
    public fun `readAll returns an entry for every key that has one`() = withStore { store ->
        store.write("a", entry("1", writtenAtMillis = 10))
        store.write("b", entry("2", writtenAtMillis = 20))

        val read = store.readAll(listOf("a", "b"))

        assertEquals(setOf("a", "b"), read.keys)
        assertEquals("1", read.getValue("a").value)
        assertEquals(20, read.getValue("b").writtenAtMillis)
    }

    @Test
    public fun `readAll omits a missing key rather than mapping it to null`() = withStore { store ->
        store.write("present", entry("1"))

        val read = store.readAll(listOf("present", "missing"))

        assertEquals(setOf("present"), read.keys, "a missing key must be absent from the map, not a null value in it")
        assertTrue("missing" !in read)
    }

    @Test
    public fun `readAll of no keys returns an empty map`() = withStore { store ->
        store.write("a", entry("1"))

        assertEquals(emptyMap(), store.readAll(emptyList()))
    }

    @Test
    public fun `readAll of only missing keys returns an empty map`() = withStore { store ->
        assertEquals(emptyMap(), store.readAll(listOf("x", "y")))
    }

    // ---------------------------------------------------------------------------------- writeAll

    @Test
    public fun `writeAll persists every entry`() = withStore { store ->
        store.writeAll(mapOf("a" to entry("1", writtenAtMillis = 1), "b" to entry("2", writtenAtMillis = 2)))

        assertEquals("1", assertNotNull(store.read("a")).value)
        assertEquals(2, assertNotNull(store.read("b")).writtenAtMillis)
    }

    @Test
    public fun `writeAll replaces previous entries for the same keys`() = withStore { store ->
        store.write("a", entry("old"))

        store.writeAll(mapOf("a" to entry("new")))

        assertEquals("new", assertNotNull(store.read("a")).value)
    }

    @Test
    public fun `writeAll of nothing is not an error`() = withStore { store ->
        store.writeAll(emptyMap())

        assertNull(store.read("anything"))
    }

    // -------------------------------------------------------------------------------- deleteMany

    @Test
    public fun `deleteMany removes the named keys`() = withStore { store ->
        store.writeAll(mapOf("a" to entry("1"), "b" to entry("2"), "c" to entry("3")))

        store.deleteMany(listOf("a", "b"))

        assertNull(store.read("a"))
        assertNull(store.read("b"))
        assertEquals("3", assertNotNull(store.read("c")).value, "an unnamed key must survive")
    }

    @Test
    public fun `deleteMany ignores keys with no entry`() = withStore { store ->
        store.write("a", entry("1"))

        store.deleteMany(listOf("a", "never-written"))

        assertNull(store.read("a"))
    }

    @Test
    public fun `deleteMany of nothing is not an error`() = withStore { store ->
        store.write("a", entry("1"))

        store.deleteMany(emptyList())

        assertEquals("1", assertNotNull(store.read("a")).value)
    }

    // ------------------------------------------------------------------------------- enumeration

    @Test
    public fun `keys either lists the stored keys or opts out with null`() = withStore { store ->
        store.write("a", entry("1"))
        store.write("b", entry("2"))

        val keys = store.keys()

        if (isEnumerable) {
            assertEquals(setOf("a", "b"), keys)
        } else {
            assertNull(keys, "a store that does not enumerate must return null, the documented opt-out")
        }
    }

    @Test
    public fun `an enumerable store reports empty rather than null when it holds nothing`() = withStore { store ->
        val keys = store.keys()

        if (isEnumerable) {
            assertEquals(
                emptySet(),
                keys,
                "empty means 'no keys'; null means 'enumeration unsupported' — conflating them narrows " +
                    "invalidateWhere from disk-wide to in-process",
            )
        } else {
            assertNull(keys)
        }
    }

    @Test
    public fun `keys stops listing a key once it is deleted`() = withStore { store ->
        assumeTrue(isEnumerable, "store does not enumerate")
        store.writeAll(mapOf("a" to entry("1"), "b" to entry("2")))

        store.delete("a")

        assertEquals(setOf("b"), store.keys())
    }

    @Test
    public fun `keysWhere selects the matching keys, or opts out exactly as keys does`() = withStore { store ->
        store.writeAll(mapOf("alpha" to entry("1"), "amber" to entry("2"), "beta" to entry("3")))

        val matched = store.keysWhere { it.startsWith("a") }

        if (isEnumerable) {
            assertEquals(setOf("alpha", "amber"), matched)
        } else {
            assertNull(matched, "keysWhere inherits keys()'s null-means-unsupported contract")
        }
    }

    @Test
    public fun `keysWhere matching nothing is empty rather than null on an enumerable store`() = withStore { store ->
        assumeTrue(isEnumerable, "store does not enumerate")
        store.write("a", entry("1"))

        assertEquals(emptySet(), store.keysWhere { false })
    }

    // ------------------------------------------------------------------------------- concurrency

    @Test
    public fun `concurrent writes to distinct keys all land`() = withStore { store ->
        val keys = (1..32).map { "k$it" }

        coroutineScope {
            keys.map { key -> async(Dispatchers.Default) { store.write(key, entry("v-$key", writtenAtMillis = 1)) } }
                .awaitAll()
        }

        val read = store.readAll(keys)
        assertEquals(keys.toSet(), read.keys, "every concurrent write must be visible afterwards")
        keys.forEach { assertEquals("v-$it", read.getValue(it).value) }
    }

    @Test
    public fun `concurrent reads never observe a torn entry`() = withStore { store ->
        // Each write pairs value "v$i" with writtenAtMillis i, so any entry whose two fields
        // disagree came from two different writes — the shape a non-atomic store would expose.
        store.write("k", entry("v0", writtenAtMillis = 0))

        coroutineScope {
            val writers = (1..16).map { i ->
                async(Dispatchers.Default) { store.write("k", entry("v$i", writtenAtMillis = i.toLong())) }
            }
            val readers = (1..16).map {
                async(Dispatchers.Default) {
                    store.read("k")?.let { assertEquals("v${it.writtenAtMillis}", it.value, "torn entry") }
                }
            }
            (writers + readers).awaitAll()
        }

        val settled = assertNotNull(store.read("k"))
        assertEquals("v${settled.writtenAtMillis}", settled.value)
    }

    @Test
    public fun `concurrent reads and deletes do not fail`() = withStore { store ->
        val keys = (1..16).map { "k$it" }
        store.writeAll(keys.associateWith { entry("v-$it") })

        coroutineScope {
            val deletes = keys.map { key -> async(Dispatchers.Default) { store.delete(key) } }
            val reads = keys.map { key -> async(Dispatchers.Default) { store.read(key) } }
            (deletes + reads).awaitAll()
        }

        assertEquals(emptyMap(), store.readAll(keys), "every key was deleted, so none should remain")
    }
}
