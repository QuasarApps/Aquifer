package io.github.quasarapps.aquifer.internal

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lincheck linearizability check of [ActiveKeyRegistry] — the hand-written compare-and-set loops
 * behind stream registration, and one of the two places in the engine where concurrency is rolled
 * by hand rather than delegated to a monitor.
 *
 * This is deliberately *not* shaped like the baseline `BoundedLruMap`/`MemoryCache` checks, whose
 * operation bodies are a single `synchronized` block: those can only catch a lock being dropped.
 * Here there is no lock at all. `register`/`unregister` retry against `putIfAbsent`/`replace`/
 * `remove`, and the value they swap is a multiset — so a lost update does not merely reorder
 * results, it leaves a key with the wrong bars, or resident when nothing is watching it. The
 * narrow key and bar spaces are the point: they force the contention that makes a losing CAS,
 * and the retry after it, actually happen.
 *
 * The read projects to plain comparable values rather than returning [ActiveBars] itself, whose
 * identity equality would make results incomparable across executions.
 *
 * Tagged `lincheck` so it runs only in the dedicated `lincheckTest` task, not `check`/`build`.
 */
@Tag("lincheck")
@Param(name = "key", gen = IntGen::class, conf = "1:2")
@Param(name = "bar", gen = IntGen::class, conf = "0:2")
class ActiveKeyRegistryLincheckTest {

    private val registry = ActiveKeyRegistry<Int>()

    /** `0` stands for the no-`maxAge` registration, which is a bar in its own right. */
    private fun bar(id: Int): Duration? = if (id == 0) null else id.seconds

    @Operation
    fun register(@Param(name = "key") key: Int, @Param(name = "bar") bar: Int) =
        registry.register(key, bar(bar))

    @Operation
    fun unregister(@Param(name = "key") key: Int, @Param(name = "bar") bar: Int) =
        registry.unregister(key, bar(bar))

    /**
     * The bars held on [key], as comparable values; empty when nothing is watching it — which is
     * also how "the key vanished when its last collector detached" is observed.
     *
     * Per *key*, deliberately. [ActiveKeyRegistry.snapshot] and `keys` iterate the
     * `ConcurrentHashMap` weakly, so they are not atomic across keys and asserting linearizability
     * over them fails on a true-but-uninteresting counterexample: one read observing a concurrent
     * registration and a later read on the same thread missing it. That is the documented contract
     * of those two views, not a defect in the loops under test — so the check stays on the per-key
     * state, which every update really does compare-and-set atomically.
     */
    @Operation
    fun barsOf(@Param(name = "key") key: Int): List<String> =
        registry.bars(key)?.bars.orEmpty().map { it.toString() }.sorted()

    @Test
    fun stressTest() = StressOptions()
        .iterations(30)
        .threads(3)
        .actorsPerThread(3)
        .check(this::class)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(30)
        .threads(3)
        .actorsPerThread(3)
        .check(this::class)
}
