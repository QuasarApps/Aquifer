package io.github.quasarapps.aquifer.test

import io.github.quasarapps.aquifer.SourceOfTruth

/**
 * Runs the published [AbstractSourceOfTruthContractTest] against [InMemorySourceOfTruth].
 *
 * This is the suite's own canary as much as the store's: the in-memory store is the reference
 * implementation consumers are pointed at, so a clause the suite gets wrong shows up here first,
 * and it is the one subject whose enumerable half is exercised without a database.
 */
class InMemorySourceOfTruthContractTest : AbstractSourceOfTruthContractTest() {

    /** It overrides `keys()`/`keysWhere()` with a real key set — see its "Enumerable" KDoc section. */
    override val isEnumerable: Boolean = true

    override fun createStore(): SourceOfTruth<String, String> = InMemorySourceOfTruth()
}
