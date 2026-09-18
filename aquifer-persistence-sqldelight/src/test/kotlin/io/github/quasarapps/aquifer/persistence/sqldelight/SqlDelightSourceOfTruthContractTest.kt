package io.github.quasarapps.aquifer.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.quasarapps.aquifer.PersistedEntry
import io.github.quasarapps.aquifer.SourceOfTruth
import io.github.quasarapps.aquifer.test.AbstractSourceOfTruthContractTest
import kotlinx.serialization.builtins.serializer

/**
 * Runs the published [AbstractSourceOfTruthContractTest] against [SqlDelightSourceOfTruth].
 *
 * The interesting subject for the *enumerable* half of the contract — it overrides `keys()`, so the
 * suite holds it to the empty-set-is-not-null clause that makes `Aquifer.invalidateWhere` disk-wide
 * — and for the bulk seams, since this store overrides `readAll`/`writeAll`/`deleteMany` with real
 * `IN`-clause and transaction implementations rather than inheriting the per-key defaults.
 */
class SqlDelightSourceOfTruthContractTest : AbstractSourceOfTruthContractTest() {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    /** It overrides `keys()` with a `SELECT`, which is what makes it the queryable adapter. */
    override val isEnumerable: Boolean = true

    override fun createStore(): SourceOfTruth<String, String> = newStore(String.serializer())

    /** Closes every driver this test opened, so an in-memory database is not left behind. */
    override fun destroyStore(store: SourceOfTruth<String, String>) {
        drivers.forEach { it.close() }
        drivers.clear()
    }

    /**
     * Stores an `Int` under [key] through a second store sharing the same database, so reading it
     * back as a `String` cannot decode. Mirrors how the adapter's own suite provokes this state.
     */
    override suspend fun writeUndecodableEntry(store: SourceOfTruth<String, String>, key: String): Boolean {
        check(store is SqlDelightSourceOfTruth<*, *>) { "unexpected store type: $store" }
        asInts.write(key, PersistedEntry(42, writtenAtMillis = 0))
        return true
    }

    // The String store and this Int store are handed the same driver, so they are two typed views
    // of one table — the only way to get a row this store cannot decode.
    private lateinit var asInts: SqlDelightSourceOfTruth<String, Int>

    private fun <V : Any> newStore(
        serializer: kotlinx.serialization.KSerializer<V>,
    ): SqlDelightSourceOfTruth<String, V> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SqlDelightSourceOfTruth.Schema.create(driver)
        drivers += driver
        asInts = SqlDelightSourceOfTruth(driver, Int.serializer(), { it }, { it })
        return SqlDelightSourceOfTruth(driver, serializer, { it }, { it })
    }
}
