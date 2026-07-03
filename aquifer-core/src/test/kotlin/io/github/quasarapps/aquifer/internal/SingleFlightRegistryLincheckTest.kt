package io.github.quasarapps.aquifer.internal

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Lincheck linearizability check of [SingleFlightRegistry], the fetch-dedup registry extracted from
 * the engine. The compare-and-set operations it relies on — [SingleFlightRegistry.registerIfAbsent]
 * (win-the-slot), [SingleFlightRegistry.remove] (value-conditional completion drop), and
 * [SingleFlightRegistry.evict] (unconditional fence drop) — plus [SingleFlightRegistry.peek] and
 * [SingleFlightRegistry.contains] must be linearizable to a plain map, which Lincheck derives by
 * running them sequentially.
 *
 * Values as well as keys are checked (`token` param): `registerIfAbsent` and `remove` are
 * value-conditional, so a torn or misordered CAS would show up as a return value no sequential
 * execution allows. The trivial pass-through members ([SingleFlightRegistry.clear],
 * [SingleFlightRegistry.keys], [SingleFlightRegistry.size]) are not driven here — they are single
 * delegations to `ConcurrentHashMap`, exercised through the engine tests and [EpochFenceTest].
 *
 * Tagged `lincheck` so it runs only in the dedicated `lincheckTest` task, not `check`/`build`.
 */
@Tag("lincheck")
@Param(name = "key", gen = IntGen::class, conf = "1:2")
@Param(name = "token", gen = IntGen::class, conf = "1:2")
class SingleFlightRegistryLincheckTest {

    private val registry = SingleFlightRegistry<Int, Int>()

    @Operation
    fun peek(@Param(name = "key") key: Int): Int? = registry.peek(key)

    @Operation
    fun contains(@Param(name = "key") key: Int): Boolean = registry.contains(key)

    @Operation
    fun registerIfAbsent(@Param(name = "key") key: Int, @Param(name = "token") token: Int): Int? =
        registry.registerIfAbsent(key, token)

    @Operation
    fun remove(@Param(name = "key") key: Int, @Param(name = "token") token: Int): Boolean =
        registry.remove(key, token)

    @Operation
    fun evict(@Param(name = "key") key: Int) = registry.evict(key)

    @Test
    fun stressTest() = StressOptions()
        .iterations(30)
        .threads(3)
        .actorsPerThread(3)
        .check(this::class)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(30)
        .threads(2)
        .actorsPerThread(3)
        .actorsBefore(2)
        .actorsAfter(1)
        .check(this::class)
}
