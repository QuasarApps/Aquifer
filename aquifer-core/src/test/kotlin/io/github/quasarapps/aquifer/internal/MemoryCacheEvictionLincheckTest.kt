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
 * Lincheck linearizability check of [MemoryCache] **with eviction in play** — the companion to
 * [MemoryCacheLincheckTest]'s plain-map (no-eviction) check. Here `maxEntries = 2` with keys `1:3`
 * forces LRU eviction, so *which* keys survive depends on access recency, and `keys()` observes the
 * surviving set. Because `get` counts as use (it reorders the access-order map), this makes `get`'s
 * side effect observable: the sequential specification Lincheck derives is the LRU-with-eviction
 * behaviour, and an unsynchronised access-order read racing a structural write would corrupt the
 * eviction order into a result that matches no sequential order. This is the coverage the no-eviction
 * check cannot reach (there, `get`'s reorder is invisible to value lookups).
 *
 * Tagged `lincheck` so it runs only in the dedicated `lincheckTest` task, not `check`/`build`.
 */
@Tag("lincheck")
@Param(name = "key", gen = IntGen::class, conf = "1:3")
@Param(name = "maxSize", gen = IntGen::class, conf = "0:2")
class MemoryCacheEvictionLincheckTest {

    private val cache = MemoryCache<Int, Int>(maxEntries = 2)

    @Operation
    fun put(@Param(name = "key") key: Int, value: Int) {
        cache.put(key, MemoryCache.Entry(value, writtenAtMillis = 0L, sequence = 0L))
    }

    @Operation
    fun get(@Param(name = "key") key: Int): Int? = cache.get(key)?.value

    @Operation
    fun remove(@Param(name = "key") key: Int) = cache.remove(key)

    @Operation
    fun keys(): Set<Int> = cache.keys()

    // The manual-shedding mutators: their access-order trimming must also linearize against
    // concurrent get/put/remove. maxSize 0:2 spans clear-all, a real trim, and a no-op.
    @Operation
    fun trimToSize(@Param(name = "maxSize") maxSize: Int) = cache.trimToSize(maxSize)

    @Operation
    fun clear() = cache.clear()

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
