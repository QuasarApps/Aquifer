package io.github.quasarapps.aquifer.test

import io.github.quasarapps.aquifer.SourceOfTruth

/**
 * Runs the published [AbstractSourceOfTruthContractTest] against [InMemorySourceOfTruth].
 *
 * This is the suite's own canary as much as the store's: the in-memory store is the reference
 * implementation consumers are pointed at, so a clause the suite gets wrong shows up here first,
 * and it is the one subject whose enumerable half is exercised without a database.
 *
 * It is also where the suite's **hook surface** is pinned — see the block below.
 */
class InMemorySourceOfTruthContractTest : AbstractSourceOfTruthContractTest() {

    /** It overrides `keys()`/`keysWhere()` with a real key set — see its "Enumerable" KDoc section. */
    override val isEnumerable: Boolean = true

    override fun createStore(): SourceOfTruth<String, String> = InMemorySourceOfTruth()

    // --- Hook surface pin ------------------------------------------------------------------
    //
    // The four overrides below restate the suite's own defaults. That looks redundant and is
    // deliberate: they exist to be a compile error, not to change behaviour.
    //
    // ROADMAP's Semver ruling makes the suite's `protected` hooks a stable surface — renaming or
    // removing one is a breaking change for every downstream store that subclasses it, which the
    // README invites them to do. Nothing mechanical enforced that: BCV does not dump the
    // test-fixtures variant, so the only thing standing in for `apiCheck` was whichever hooks the
    // in-repo adapters happened to override. `persistsValidator` and `persistsServerFreshFor` were
    // overridden nowhere, so either could have been renamed or dropped with `./gradlew build`
    // still green.
    //
    // Overriding every hook in one place turns any such rename into an `overrides nothing` error
    // here, before it is one in a consumer's build. This subject is the right home for it: it needs
    // no database or temporary directory, so the pin costs nothing to run, and a reader who lands
    // here is already looking at the reference implementation.
    //
    // Note this does not pin the hooks' *defaults*, only their existence and shape. Flipping a
    // default is permitted under the ruling (it changes what the suite asserts, not the surface),
    // and stays invisible to the compiler by design.

    override fun destroyStore(store: SourceOfTruth<String, String>) = Unit

    override val persistsValidator: Boolean get() = true

    override val persistsServerFreshFor: Boolean get() = true

    override suspend fun writeUndecodableEntry(
        store: SourceOfTruth<String, String>,
        key: String,
    ): Boolean = false
}
