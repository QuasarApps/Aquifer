package io.github.quasarapps.aquifer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Batched fetching: `batchFetcher` + `getAll` collapse N keys into one backend call. */
class BatchFetchingTest {

    @Test
    fun `getAll batches all missing keys into one backend call`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }

        assertEquals(
            mapOf("a" to 1, "bb" to 2, "ccc" to 3),
            store.getAll(setOf("a", "bb", "ccc")),
        )
        assertEquals(listOf(setOf("a", "bb", "ccc")), batches, "one call for all three keys")
    }

    @Test
    fun `getAll serves fresh cached keys and batches only the rest`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }
        store.put("a", 100) // fresh (default TTL is infinite)

        assertEquals(mapOf("a" to 100, "bb" to 2), store.getAll(setOf("a", "bb")))
        assertEquals(listOf(setOf("bb")), batches, "the fresh key is not refetched")
    }

    @Test
    fun `getAll omits keys the batch fetcher leaves out and reports them`() = runTest {
        val failures = mutableMapOf<String, Throwable>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys -> keys.filter { it != "miss" }.associateWith { it.length } }
            events(object : AquiferEvents<String> {
                override fun onFetchFailed(key: String, error: Throwable, attempts: Int) {
                    failures[key] = error
                }
            })
        }

        assertEquals(mapOf("a" to 1), store.getAll(setOf("a", "miss")))
        assertIs<BatchKeyMissingException>(failures["miss"])
        assertNull(failures["a"], "the present key succeeded")
    }

    @Test
    fun `a throwing batch fetcher fails every key without throwing to getAll`() = runTest {
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { throw IOException("backend down") }
        }

        assertEquals(emptyMap(), store.getAll(setOf("a", "b")))
    }

    @Test
    fun `getAll joins an in-flight single fetch instead of re-requesting`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys ->
                batches += keys
                gate.await()
                keys.associateWith { it.length }
            }
        }

        val single = async { store.get("a") } // batch of one, gated
        settle() // registers inFlight["a"], suspends in the batch call
        val all = async { store.getAll(setOf("a", "b")) } // "a" joins; only "b" is batched
        settle()
        gate.complete(Unit)

        assertEquals(1, single.await())
        assertEquals(mapOf("a" to 1, "b" to 1), all.await())
        assertEquals(listOf(setOf("a"), setOf("b")), batches, "\"a\" was not requested twice")
    }

    @Test
    fun `getAll with CacheOnly returns only the cached subset and never fetches`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }
        store.put("a", 100)

        assertEquals(mapOf("a" to 100), store.getAll(setOf("a", "b"), Freshness.CacheOnly))
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `getAll with NetworkOnly batches even cached keys`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }
        store.put("a", 100)

        assertEquals(mapOf("a" to 1), store.getAll(setOf("a"), Freshness.NetworkOnly))
        assertEquals(listOf(setOf("a")), batches, "NetworkOnly ignores the cached value")
    }

    @Test
    fun `a single get over a batch fetcher works as a batch of one`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }

        assertEquals(3, store.get("abc"))
        assertEquals(listOf(setOf("abc")), batches)
    }

    @Test
    fun `getAll falls back to individual fetches without a batch fetcher`() = runTest {
        var calls = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            fetcher {
                calls++
                it.length
            }
        }

        assertEquals(mapOf("a" to 1, "bb" to 2), store.getAll(setOf("a", "bb")))
        assertEquals(2, calls, "a plain fetcher resolves keys individually")
    }

    @Test
    fun `getAll serves stale values when the batch fetch fails`() = runTest {
        val clock = FakeClock()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            clock(clock)
            freshness { timeToLive = 1.minutes }
            batchFetcher { throw IOException("down") }
        }
        store.put("a", 100)
        clock.advanceBy(5.minutes) // stale

        // CacheFirst: stale ⇒ fetch ⇒ fails ⇒ stale-if-error falls back to the cached value.
        assertEquals(mapOf("a" to 100), store.getAll(setOf("a")))
    }

    @Test
    fun `getAll on an empty key set never calls the fetcher`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }

        assertEquals(emptyMap(), store.getAll(emptySet()))
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `the retry policy wraps both a single fetch and the multi-key batch call`() = runTest {
        var singleAttempts = 0
        var batchAttempts = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            retry {
                maxAttempts = 3
                initialDelay = 1.milliseconds
            }
            batchFetcher { keys ->
                if (keys.size == 1) singleAttempts++ else batchAttempts++
                throw IOException("down")
            }
        }

        // A single get is a batch of one: the retry policy applies (3 attempts).
        assertFailsWith<IOException> { store.get("a") }
        assertEquals(3, singleAttempts)

        // The multi-key batch call is retried too (whole-batch retry, RFC #29 phase 2): 3
        // attempts, then every key drops to its (absent) cached fallback.
        assertEquals(emptyMap(), store.getAll(setOf("x", "y")))
        assertEquals(3, batchAttempts)
    }

    @Test
    fun `whole-batch retry re-runs the entire batch and fires onFetchRetried per key`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val retried = mutableListOf<Pair<String, Int>>()
        var attempts = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            retry {
                maxAttempts = 3
                initialDelay = 1.milliseconds
            }
            batchFetcher { keys ->
                batches += keys
                if (++attempts < 3) throw IOException("transient") else keys.associateWith { it.length }
            }
            events(object : AquiferEvents<String> {
                override fun onFetchRetried(key: String, attempt: Int, error: Throwable, nextDelay: Duration) {
                    retried += key to attempt
                }
            })
        }

        assertEquals(mapOf("a" to 1, "bb" to 2), store.getAll(setOf("a", "bb")))
        assertEquals(3, attempts, "two transient failures, then success")
        // Retry-all: the whole batch (both keys) re-ran on every attempt, never a failed slice.
        assertEquals(List(3) { setOf("a", "bb") }, batches)
        // onFetchRetried fired for every key on each of the two retries.
        assertEquals(setOf("a" to 1, "bb" to 1, "a" to 2, "bb" to 2), retried.toSet())
    }

    @Test
    fun `a key omitted from a successful batch is a definitive miss, never retried`() = runTest {
        var attempts = 0
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            retry {
                maxAttempts = 3
                initialDelay = 1.milliseconds
            }
            // The batch succeeds but omits "miss"; omission is a miss, not a transient failure.
            batchFetcher { keys ->
                attempts++
                keys.filter { it != "miss" }.associateWith { it.length }
            }
        }

        assertEquals(mapOf("a" to 1), store.getAll(setOf("a", "miss")))
        assertEquals(1, attempts, "a successful-but-partial batch is not retried")
    }

    @Test
    fun `a terminal batch failure reports the batch attempt count to onFetchFailed per key`() = runTest {
        val failures = mutableMapOf<String, Int>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            retry {
                maxAttempts = 3
                initialDelay = 1.milliseconds
            }
            batchFetcher { throw IOException("down") }
            events(object : AquiferEvents<String> {
                override fun onFetchFailed(key: String, error: Throwable, attempts: Int) {
                    failures[key] = attempts
                }
            })
        }

        assertEquals(emptyMap(), store.getAll(setOf("a", "b")))
        assertEquals(mapOf("a" to 3, "b" to 3), failures, "every key reports all 3 batch attempts")
    }

    @Test
    fun `configuring a batch fetcher alongside another fetcher is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            aquifer<String, Int> {
                fetcher { 1 }
                batchFetcher { keys -> keys.associateWith { 1 } }
            }
        }
    }

    @Test
    fun `getAll on a closed store throws`() = runTest {
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys -> keys.associateWith { it.length } }
        }
        store.close()

        assertFailsWith<IllegalStateException> { store.getAll(setOf("a")) }
    }

    @Test
    fun `closing the store during getAll surfaces AquiferException, not bare cancellation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys ->
                gate.await() // hold the batch in flight
                keys.associateWith { it.length }
            }
        }

        // runCatching inside the async so the result is captured, not propagated (the
        // established pattern for the close-during-fetch contract in AquiferLifecycleTest).
        val pending = async { runCatching { store.getAll(setOf("a", "b")) } }
        settle() // getAll has dispatched the batch and is awaiting it
        store.close() // cancels the store scope and the in-flight batch

        // The caller gets a typed AquiferException, not a silent coroutine cancellation.
        assertIs<AquiferException>(pending.await().exceptionOrNull())
    }

    @Test
    fun `closing the store mid-chunk stops dispatching the remaining chunks`() = runTest {
        val seen = mutableListOf<Set<String>>()
        lateinit var store: Aquifer<String, Int>
        store = aquifer<String, Int> {
            scope(backgroundScope)
            // A synchronous fetcher gives the sequential dispatch loop no suspension point between
            // chunks, so cancellation is only observed by the explicit ensureActive() check. Without
            // it, closing the store during chunk 1 would still fire the four later chunk calls.
            batchFetcher(maxBatchSize = 1) { keys ->
                seen += keys
                if (seen.size == 1) store.close() // cancel the store scope during the first chunk
                keys.associateWith { it.length }
            }
        }

        runCatching { store.getAll(linkedSetOf("a", "b", "c", "d", "e")) }
        settle()

        assertEquals(listOf(setOf("a")), seen, "no chunk dispatched after the store scope was cancelled")
    }

    @Test
    fun `getAll splits the fetch into maxBatchSize chunks`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher(maxBatchSize = 2) { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }

        assertEquals(
            mapOf("a" to 1, "bb" to 2, "ccc" to 3, "dddd" to 4, "eeeee" to 5),
            store.getAll(linkedSetOf("a", "bb", "ccc", "dddd", "eeeee")),
        )
        assertEquals(3, batches.size, "five keys, capped at two, dispatch as three chunks")
        assertTrue(batches.all { it.size <= 2 }, "no chunk exceeds maxBatchSize")
        assertEquals(
            listOf("a", "bb", "ccc", "dddd", "eeeee"),
            batches.flatten().sorted(),
            "every key is fetched exactly once, across the chunks",
        )
    }

    @Test
    fun `a failing chunk fails only its own keys, not the surviving chunk`() = runTest {
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            // maxBatchSize = 2 over four keys makes two multi-key chunks, [a, b] and [c, d]. A
            // throw sinks its *whole* chunk (batch-fetcher contract), so failing on "b" loses "a"
            // too — but the other chunk, plural, must still resolve. maxBatchSize = 1 would prove
            // nothing here: every chunk would be a singleton.
            batchFetcher(maxBatchSize = 2) { keys ->
                if ("b" in keys) throw IOException("first chunk down")
                keys.associateWith { it.length }
            }
        }

        assertEquals(mapOf("c" to 1, "d" to 1), store.getAll(linkedSetOf("a", "b", "c", "d")))
    }

    @Test
    fun `conditionalBatchFetcher splits the fetch into maxBatchSize chunks`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            conditionalBatchFetcher(maxBatchSize = 2) { validators ->
                batches += validators.keys
                validators.keys.associateWith { FetchResult.Fresh(it.length) }
            }
        }

        store.getAll(linkedSetOf("a", "bb", "ccc"))
        assertEquals(2, batches.size, "three keys, capped at two, dispatch as two chunks")
        assertTrue(batches.all { it.size <= 2 }, "no chunk exceeds maxBatchSize")
        assertEquals(listOf("a", "bb", "ccc"), batches.flatten().sorted())
    }

    @Test
    fun `a whole-set batch fetcher without a cap stays one call`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher { keys -> // no maxBatchSize: unchanged, one call for the set
                batches += keys
                keys.associateWith { it.length }
            }
        }

        store.getAll(linkedSetOf("a", "bb", "ccc", "dddd", "eeeee"))
        assertEquals(1, batches.size, "the default is a single unbounded call")
    }

    @Test
    fun `chunks dispatch sequentially, one call at a time`() = runTest {
        val started = mutableListOf<Set<String>>()
        val gate = CompletableDeferred<Unit>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            // A backend that caps ids per request usually caps concurrency too, so the chunks
            // must go out one at a time. The first chunk parks on the gate; were dispatch
            // concurrent, the second chunk's call would be recorded while the gate is held.
            batchFetcher(maxBatchSize = 2) { keys ->
                started += keys
                if (started.size == 1) gate.await()
                keys.associateWith { it.length }
            }
        }

        val result = async { store.getAll(linkedSetOf("a", "bb", "ccc", "dddd")) }
        settle()
        assertEquals(1, started.size, "the second chunk waits until the first call completes")

        gate.complete(Unit)
        assertEquals(mapOf("a" to 1, "bb" to 2, "ccc" to 3, "dddd" to 4), result.await())
        assertEquals(2, started.size, "the second chunk dispatched only after the first finished")
    }

    @Test
    fun `a fire-and-forget prefetchAll chunks by maxBatchSize too`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            batchFetcher(maxBatchSize = 2) { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }

        store.prefetchAll(linkedSetOf("a", "bb", "ccc", "dddd", "eeeee")) // returns having only launched
        // The fetch decision, slice registration, and the sequential chunk calls all happen inside the
        // launched store-scope coroutine; none of it blocks, so settle() drains the whole chain. A
        // key that fell out of the chunking into a batch-of-one would show as a fourth entry, so the
        // count of 3 is the proof the fire-and-forget path chunked.
        settle()
        assertEquals(3, batches.size, "the cap bounds the fire-and-forget path, not just getAll")
        assertTrue(batches.all { it.size <= 2 }, "no chunk exceeds maxBatchSize")
        // The chunked results actually reached the cache: a later CacheOnly read hits, no new fetch.
        assertEquals(5, store.get("eeeee", Freshness.CacheOnly))
        assertEquals(3, batches.size, "the CacheOnly read did not add a batch-of-one call")
    }

    @Test
    fun `the coalescing overload's maxBatchSize also bounds an explicit getAll`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val store = aquifer<String, Int> {
            scope(backgroundScope)
            // getAll dispatches its own keys immediately (ignoring the window) — but the size cap
            // set on the coalescing overload must still split that immediate dispatch into chunks.
            batchFetcher(coalesceWindow = 1.minutes, maxBatchSize = 2) { keys ->
                batches += keys
                keys.associateWith { it.length }
            }
        }

        store.getAll(linkedSetOf("a", "bb", "ccc", "dddd", "eeeee"))
        assertEquals(3, batches.size, "five keys, capped at two, dispatch as three chunks")
        assertTrue(batches.all { it.size <= 2 }, "no chunk exceeds maxBatchSize")
    }

    @Test
    fun `a non-positive maxBatchSize is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            aquifer<String, Int> { batchFetcher(maxBatchSize = 0) { keys -> keys.associateWith { 1 } } }
        }
        assertFailsWith<IllegalArgumentException> {
            aquifer<String, Int> {
                conditionalBatchFetcher(maxBatchSize = 0) { validators ->
                    validators.keys.associateWith { FetchResult.Fresh(1) }
                }
            }
        }
    }
}
