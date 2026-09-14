package io.github.quasarapps.aquifer

/**
 * Minimal in-memory [SourceOfTruth] for tests that assert on persisted contents. Deliberately
 * **non-enumerable**: it does not override [keys]/[keysWhere], so both keep their `null` default
 * ("enumeration unsupported"), which is exactly what the enumeration-fallback tests
 * ([KeyEnumerationTest], [InvalidateWhereTest]) need. The published, *enumerable*
 * `io.github.quasarapps.aquifer.test.InMemorySourceOfTruth` (aquifer-test) is the reference
 * fixture for the opposite case; the two are intentionally distinct doubles, kept separate so a
 * test's choice of enumerability is visible in the type it names.
 */
class NonEnumerableSourceOfTruth<K : Any, V : Any> : SourceOfTruth<K, V> {

    val storage = mutableMapOf<K, PersistedEntry<V>>()

    override suspend fun read(key: K): PersistedEntry<V>? = storage[key]

    override suspend fun write(key: K, entry: PersistedEntry<V>) {
        storage[key] = entry
    }

    override suspend fun delete(key: K) {
        storage.remove(key)
    }

    override suspend fun deleteAll() {
        storage.clear()
    }
}
