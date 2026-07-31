package io.github.quasarapps.aquifer.sample

import io.github.quasarapps.aquifer.Aquifer
import io.github.quasarapps.aquifer.AquiferEvents
import io.github.quasarapps.aquifer.DataState
import io.github.quasarapps.aquifer.FetchResult
import io.github.quasarapps.aquifer.Freshness
import io.github.quasarapps.aquifer.aquifer
import io.github.quasarapps.aquifer.persistence.jsonFileSourceOfTruth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * A runnable tour of Aquifer. The core loop first — stale-while-revalidate streams, applying an
 * already-confirmed change to the cache, retries against a flaky API, surviving a "process
 * restart" via disk persistence, refresh-on-reconnect — then the features a real app reaches for:
 * single-flight de-duplication, prefetch, batched multi-key fetching, conditional (304) fetching,
 * negative caching, and the `stats`/`snapshot` counters.
 *
 * Not covered here, because both need a `SourceOfTruth` configured for them rather than a scenario:
 * encryption at rest (`ValueCipher`) and schema migration. See the README.
 *
 * Run it with: `./gradlew :sample:run`
 */
@Serializable
data class Article(val id: Int, val title: String, val revision: Int)

/** Fake backend: slow, and every third call fails to show off retries. */
class FlakyArticlesApi {
    private val calls = AtomicInteger()
    private val revisions = AtomicInteger()

    suspend fun fetchArticle(id: Int): Article {
        delay(150) // simulated network latency
        val call = calls.incrementAndGet()
        if (call % 3 == 0) throw IOException("simulated outage on call #$call")
        return Article(id, "Article #$id", revisions.incrementAndGet())
    }

    /**
     * Accepts a revision the backend now considers authoritative, so later fetches return it or
     * newer. Scenario 3 writes the same revision into the cache: a confirmed change has to land on
     * both sides, or the next fetch would "revert" it with older server state.
     */
    fun acceptRevision(revision: Int) = revisions.set(revision)
}

/** Fake backend that just counts, so a scenario can show how many round-trips really happened. */
class CountingArticlesApi {
    private val calls = AtomicInteger()

    suspend fun fetchArticle(id: Int): Article {
        delay(150)
        log("api:   call #${calls.incrementAndGet()} for $id")
        return Article(id, "Article #$id", revision = 1)
    }

    fun calls(): Int = calls.get()
}

/** Fake backend that resolves many ids in one call, so a list screen costs one round-trip. */
class BatchArticlesApi {
    private val calls = AtomicInteger()

    suspend fun fetchArticles(ids: Set<Int>): Map<Int, Article> {
        delay(150)
        log("api:   batch call #${calls.incrementAndGet()} for ids ${ids.sorted()}")
        return ids.associateWith { Article(it, "Article #$it", revision = 1) }
    }
}

/** Fake backend that speaks validators, so an unchanged article costs no payload. */
class RevalidatingArticlesApi {
    private val etag = "W/\"v1\""

    suspend fun fetchArticle(id: Int, validator: String?): FetchResult<Article> {
        delay(150)
        return if (validator == etag) {
            log("api:   304 Not Modified for $id (sent If-None-Match: $etag)")
            FetchResult.NotModified
        } else {
            log("api:   200 OK for $id (returning ETag $etag)")
            FetchResult.Fresh(Article(id, "Article #$id", revision = 1), validator = etag)
        }
    }
}

/** Fake backend that is simply down, so a scenario can show a failing key being asked once. */
class DownArticlesApi {
    private val calls = AtomicInteger()

    suspend fun fetchArticle(id: Int): Article {
        delay(50)
        log("api:   call #${calls.incrementAndGet()} for $id -> 503")
        throw IOException("service unavailable")
    }

    fun calls(): Int = calls.get()
}

/** Logs engine activity — in an app this would be Timber/analytics. */
class LoggingEvents : AquiferEvents<Int> {
    override fun onFetchStarted(key: Int) = log("event: fetch started for $key")
    override fun onFetchSucceeded(key: Int, duration: Duration) = log("event: fetch for $key took $duration")
    override fun onFetchRetried(key: Int, attempt: Int, error: Throwable, nextDelay: Duration) =
        log("event: attempt #$attempt for $key failed (${error.message}); retrying in $nextDelay")

    override fun onFetchFailed(key: Int, error: Throwable, attempts: Int) =
        log("event: fetch for $key failed for good after $attempts attempt(s)")

    override fun onFetchSuppressed(key: Int, error: Throwable, remaining: Duration) =
        log("event: fetch for $key suppressed for another $remaining (${error.message})")
}

fun main(): Unit = runBlocking {
    val cacheDir = Path("build/sample-cache")
    coreLoopTour(cacheDir)
    featureTour()
    banner("Done. Cache files live in $cacheDir (cleared at startup so every run is identical).")
}

/** Scenarios 1-5: the loop every screen runs, from cold start to reconnect. */
private suspend fun CoroutineScope.coreLoopTour(cacheDir: Path) {
    val api = FlakyArticlesApi()
    val reconnected = MutableSharedFlow<Unit>()

    fun newProcess(): Aquifer<Int, Article> = aquifer {
        scope(this@coreLoopTour)
        fetcher { id -> api.fetchArticle(id) }
        freshness { timeToLive = 1.seconds }
        retry {
            maxAttempts = 3
            initialDelay = 100.milliseconds
        }
        persistence(jsonFileSourceOfTruth(cacheDir))
        events(LoggingEvents())
    }.also { it.revalidateOn(reconnected) }

    banner("1. Cold start: stream renders Loading, then the fetched article")
    val firstProcess = newProcess()
    firstProcess.invalidateAll() // start from an empty cache so every run tells the same story
    val ui = launch {
        firstProcess.stream(1).collect { state -> log("ui:    ${state.render()}") }
    }
    delay(600)

    banner("2. Stale-while-revalidate: after the TTL, cached data shows instantly while refreshing")
    delay(1.seconds) // let the entry go stale
    log("ui:    (user reopens the screen)")
    val stale = firstProcess.get(1, Freshness.StaleWhileRevalidate)
    log("get -> \"${stale.title}\" rev=${stale.revision} (served stale immediately; refresh runs in background)")
    delay(600)

    banner("3. A confirmed change (here: a server push) broadcasts to every observer")
    // `put` applies data the server has already accepted. It is deliberately not an offline-edit
    // outbox: the next fetch replaces it, so an unsynced user edit written this way would be lost.
    // The push updates the backend too, so scenario 5's refresh returns revision 100 rather than
    // reverting to older server state.
    api.acceptRevision(99)
    firstProcess.put(1, Article(1, "Article #1 (revised upstream)", revision = 99))
    delay(200)

    banner("4. 'Process death': a brand-new store serves the last data from disk, no network")
    ui.cancelAndJoin()
    firstProcess.close()
    val secondProcess = newProcess()
    val restored = secondProcess.get(1)
    log("get -> \"${restored.title}\" rev=${restored.revision} - straight from disk")

    banner("5. Reconnect: stale active streams refresh; every 3rd API call fails, so watch a retry")
    val ui2 = launch {
        secondProcess.stream(1).collect { state -> log("ui:    ${state.render()}") }
    }
    delay(1200) // entry goes stale while "offline"
    log("       (connectivity returns)")
    reconnected.emit(Unit)
    delay(900)

    ui2.cancelAndJoin()
    secondProcess.close()
}

/**
 * Scenarios 6-11: what a real app reaches for once the core loop works. Each gets its own store
 * and its own purpose-built fake backend, then they are closed and their counters read — `stats`
 * and `snapshot` are safe on a closed store, so a teardown path can still report what happened.
 */
private suspend fun CoroutineScope.featureTour() {
    val stores = listOf(
        "warm store" to singleFlightAndPrefetch(),
        "list feed" to batchedListScreen(),
        "etag store" to conditionalRevalidation(),
        "dead endpoint" to negativeCaching(),
    )
    stores.forEach { (_, store) -> store.close() }

    banner("11. stats() and snapshot(): the numbers behind a debug overlay")
    for ((name, store) in stores) {
        val stats = store.stats()
        log(
            "$name: ${stats.hits} hit(s) / ${stats.misses} miss(es), " +
                "hit rate ${(stats.hitRate * 100).roundToInt()}%, keys in memory ${store.snapshot().sorted()}",
        )
    }
}

/** Scenarios 6-7: concurrent readers share one fetch, and prefetch moves it off the critical path. */
private suspend fun CoroutineScope.singleFlightAndPrefetch(): Aquifer<Int, Article> {
    val api = CountingArticlesApi()
    val store = aquifer<Int, Article> {
        scope(this@singleFlightAndPrefetch)
        fetcher { id -> api.fetchArticle(id) }
        events(LoggingEvents())
    }

    banner("6. Single-flight: five readers hit a cold key at once and share one fetch")
    val readers = (1..5).map { async { store.get(7) } }.awaitAll()
    log("5 concurrent get(7) calls -> ${readers.size} results from ${api.calls()} API call(s)")

    banner("7. Prefetch: warm the next screen while the user is still on this one")
    store.prefetch(8)
    log("prefetch(8) returned without blocking; the fetch runs in the store's scope")
    delay(400)
    val read = measureTimedValue { store.get(8) }
    log("get(8) -> \"${read.value.title}\" in ${read.duration} - no network on the critical path")
    return store
}

/** Scenario 8: one round-trip resolves a whole list screen — the cure for the N+1 fetch. */
private suspend fun CoroutineScope.batchedListScreen(): Aquifer<Int, Article> {
    val api = BatchArticlesApi()
    val store = aquifer<Int, Article> {
        scope(this@batchedListScreen)
        batchFetcher { ids -> api.fetchArticles(ids) }
        events(LoggingEvents())
    }

    banner("8. Batching: a list screen costs one round-trip, not one per row")
    val page = store.getAll(setOf(11, 12, 13, 14, 15))
    log("getAll(11..15) -> ${page.size} articles in one batch call")
    log("       (five per-key events above, one call: batching is a transport detail, not a coarser unit)")
    log("       (the user scrolls back up)")
    store.getAll(setOf(11, 12, 13))
    log("getAll(11..13) -> served from cache; note there is no second batch call above")
    return store
}

/** Scenario 9: a validator turns an unchanged article into a 304, so nothing is re-downloaded. */
private suspend fun CoroutineScope.conditionalRevalidation(): Aquifer<Int, Article> {
    val api = RevalidatingArticlesApi()
    val store = aquifer<Int, Article> {
        scope(this@conditionalRevalidation)
        conditionalFetcher { id, validator -> api.fetchArticle(id, validator) }
        events(LoggingEvents())
    }

    banner("9. Conditional fetching: revalidating an unchanged article costs no payload")
    val downloaded = store.get(21)
    log("get(21) -> \"${downloaded.title}\" (downloaded; the ETag is stored with the entry)")
    log("       (the screen is reopened and asks for a guaranteed-fresh value)")
    val revalidated = store.fresh(21)
    log("fresh(21) -> \"${revalidated.title}\" - the server said 304, so the cached value stands")
    return store
}

/** Scenario 10: a dead endpoint is asked once; later readers fail fast on the remembered error. */
private suspend fun CoroutineScope.negativeCaching(): Aquifer<Int, Article> {
    val api = DownArticlesApi()
    val store = aquifer<Int, Article> {
        scope(this@negativeCaching)
        fetcher { id -> api.fetchArticle(id) }
        negativeCache { timeToLive = 30.seconds }
        events(LoggingEvents())
    }

    banner("10. Negative caching: a dead endpoint is asked once, not once per reader")
    repeat(3) { attempt ->
        val failure = runCatching { store.get(31) }.exceptionOrNull()
        log("read #${attempt + 1} -> failed with \"${failure?.message}\"")
    }
    log("3 reads cost ${api.calls()} API call(s); the rest were suppressed and rethrew the same error")
    return store
}

private fun DataState<Article>.render(): String = when (this) {
    is DataState.Loading -> "[loading] showing: ${value?.title ?: "nothing yet"}"
    is DataState.Content ->
        "[content] ${value.title} rev=${value.revision} (${origin}${if (isStale) ", stale" else ""})"
    is DataState.Failure -> "[failure] ${error.message} - still showing: ${value?.title ?: "nothing"}"
    is DataState.Empty -> "[empty] affirmatively nothing cached"
}

private fun banner(text: String) {
    println()
    println("-".repeat(72))
    println(text)
    println("-".repeat(72))
}

private fun log(message: String) = println("  $message")
