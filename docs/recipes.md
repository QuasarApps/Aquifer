# Recipes

The [README](../README.md) explains each knob once. The questions that come up in practice are
*combinations* — how the knobs compose for a particular shape of data. Each recipe below is a
self-contained, compilable snippet plus the reasoning behind it.

Snippets import from `io.github.quasarapps.aquifer` (the core `aquifer { }` builder and
`Aquifer`/`DataState` types); recipes that reach other modules name the extra dependency inline
(`aquifer-okhttp` for `HttpException`, `aquifer-persistence-file` for `jsonFileSourceOfTruth`).
Identifiers like `api`, `cacheDir` and `render` are stand-ins for your own.

- [A singleton: one keyless value](#a-singleton-one-keyless-value)
- [Modelling "404 is a value"](#modelling-404-is-a-value)
- [Search and autocomplete](#search-and-autocomplete)
- [Tenant scoping and logout](#tenant-scoping-and-logout)
- [Data-class keys that survive refactors](#data-class-keys-that-survive-refactors)
- [Blocking fetchers and `Dispatchers.IO`](#blocking-fetchers-and-dispatchersio)

## A singleton: one keyless value

App config, the signed-in session, a feature-flag bundle — data that has exactly one value, not a
keyed family. Aquifer keys every entry, so the idiom is a `Unit` key:

```kotlin
import io.github.quasarapps.aquifer.Aquifer
import io.github.quasarapps.aquifer.aquifer
import kotlin.time.Duration.Companion.minutes

data class AppConfig(val flags: Map<String, Boolean>, val minSupportedVersion: Int)

val config: Aquifer<Unit, AppConfig> = aquifer {
    fetcher { _ -> api.fetchConfig() }   // the key is always Unit
    freshness { timeToLive = 15.minutes }
    memoryCache { maxEntries = 1 }        // one key → one slot
}

// Read the current value (fetching if the single entry is stale or absent):
val current: AppConfig = config.get(Unit)

// Or observe it — every screen sees the same value and the same refresh:
config.stream(Unit).collect { state -> render(state) }
```

Everything else works unchanged: a burst of `get(Unit)` calls shares one single-flight fetch,
`StaleWhileRevalidate` serves the last config while revalidating, and `put(Unit, …)` writes a value
locally. Cap memory at one slot, since there is only ever one key.

## Modelling "404 is a value"

`Aquifer<K : Any, V : Any>` never stores `null`, so **absence has to be modelled *in* `V`** — to the
store, a fetcher that throws is a *failure*, not an empty. Left alone, a `404` from an OkHttp fetcher
arrives as `HttpException(404, …)`, every fetch-capable `stream` renders `Failure`, and (if
configured) the [negative cache](../README.md#negative-caching) remembers it as a failure and
suppresses refetches. That is exactly right when `404` means "error".

When `404` instead means *"this key legitimately has no value yet"*, catch it in the fetcher and
return a sentinel that your `V` can represent:

```kotlin
import io.github.quasarapps.aquifer.aquifer
import io.github.quasarapps.aquifer.okhttp.HttpException   // aquifer-okhttp
import kotlin.time.Duration.Companion.minutes

sealed interface Profile {
    data class Found(val user: User) : Profile
    data object Missing : Profile   // a 404 is a value here, not a failure
}

val profiles = aquifer<UserId, Profile> {
    fetcher { id ->
        try {
            Profile.Found(api.fetchUser(id))
        } catch (e: HttpException) {
            if (e.code == 404) Profile.Missing else throw e   // 404 → value; anything else fails
        }
    }
    freshness { timeToLive = 5.minutes }
}
```

Now a missing profile caches and streams as `Content(Profile.Missing)` — an ordinary value the UI can
render and the store can serve offline — rather than a `Failure` that retries and negative-caches.

> `DataState.Empty` is **not** the tool for this. `Empty` is emitted only to `CacheOnly` streams on a
> genuine cache miss or an observed `invalidate`; it is never how a fetcher reports "not found". Model
> the absence in `V`, as above.

## Search and autocomplete

Key = the query string. Two things make this shape distinctive: queries arrive keystroke-fast, and
the key space is unbounded. Coalesce the fast fetches into one call, and bound the failure memory:

```kotlin
import io.github.quasarapps.aquifer.aquifer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val search = aquifer<String, SearchResults> {
    // Fold individual get/stream fetches landing within the window into one backend call;
    // it flushes when the window elapses or once maxBatchSize distinct queries accumulate.
    batchFetcher(coalesceWindow = 20.milliseconds, maxBatchSize = 25) { queries ->
        api.searchBatch(queries)   // Map<String, SearchResults>; a query absent from the map fails only itself
    }
    freshness { timeToLive = 2.minutes }
    // Distinct queries are a boundless key space, so bound how many failures are remembered.
    negativeCache {
        timeToLive = 10.seconds
        maxEntries = 200   // LRU-evict the least-recently-consulted failure record
    }
}

// Each collector can set its own staleness bar — a suggestion box tolerates slightly old results.
// maxAge is the third parameter of stream(), so pass it by name:
search.stream(query, maxAge = 30.seconds).collect { render(it) }
```

`batchFetcher(coalesceWindow, maxBatchSize)` turns a run of keystrokes into a single request, and
`negativeCache { maxEntries }` keeps a stream of distinct failing queries from growing the failure
memory without limit. `maxAge` lets a collector accept results up to that old before revalidating.

## Tenant scoping and logout

Two related wipes, with one sharp edge — how far each reaches into persistence.

```kotlin
// Drop one tenant's entries. Reaches memory always; reaches disk only on an enumerable store.
cache.invalidateWhere { key -> key.tenantId == leavingTenant }

// Logout: a full reset that clears memory AND disk on every store.
cache.invalidateAll()
```

`invalidateWhere(predicate)` drops every in-process key the predicate matches, and reaches *persisted*
keys **only when the `SourceOfTruth` can enumerate them**: `SqlDelightSourceOfTruth` can, so there its
`invalidateWhere` is disk-wide; the default `JsonFileSourceOfTruth` cannot — its filenames are a
one-way SHA-256 of the key — so its `invalidateWhere` is **memory-only**. For a logout that must clear
cached data on disk under the file store, use `invalidateAll()`: it clears memory and disk on every
store, and because it advances the epoch fence, responses already in flight for the previous user
cannot land back in the cache after the reset.

Keep the predicate pure and side-effect-free — it may be evaluated more than once for the same key.

## Data-class keys that survive refactors

A data class makes a fine key: its value-based `equals`/`hashCode` mean it works in memory with no
extra effort. Persistence adds a requirement, though — the key's **string encoding** names the
on-disk entry (it is SHA-256'd into the filename, and used as the cipher's associated data). The file
store defaults that encoding to `toString()`, whose output for a data class includes field *names and
order*. Reorder or rename a field in a later refactor and every cached file silently orphans (and any
value-based-`hashCode` TTL jitter re-rolls). Pin an explicit, stable encoding instead:

```kotlin
import io.github.quasarapps.aquifer.aquifer
import io.github.quasarapps.aquifer.persistence.jsonFileSourceOfTruth   // aquifer-persistence-file

data class ArticleKey(val locale: String, val slug: String)

val articles = aquifer<ArticleKey, Article> {
    fetcher { key -> api.fetchArticle(key.locale, key.slug) }
    freshness { timeToLive = 10.minutes }
    persistence(
        jsonFileSourceOfTruth(
            directory = cacheDir.resolve("articles"),
            // Stable across refactors — unlike toString(), which changes if fields move or rename.
            keyEncoder = { "${it.locale}/${it.slug}" },
        ),
    )
}
```

Choose an encoding that is **injective** (distinct keys never collide) and stable across refactors.
`SqlDelightSourceOfTruth` goes one step further: it takes both a `keyEncode` and a `keyDecode` that
inverts it, because it reconstructs the original keys when enumerating for `invalidateWhere`.

## Blocking fetchers and `Dispatchers.IO`

A fetcher runs on the store's coroutine scope, which defaults to `Dispatchers.Default` — a CPU-sized
pool. A fetcher that *blocks* its thread (a synchronous HTTP client, JDBC, blocking file I/O) starves
that pool and can stall unrelated fetches. Confine the blocking work to an I/O dispatcher:

```kotlin
import io.github.quasarapps.aquifer.aquifer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes

val reports = aquifer<ReportId, Report> {
    fetcher { id ->
        withContext(Dispatchers.IO) {   // JDBC / synchronous HTTP / blocking file I/O
            legacyJdbc.loadReport(id)
        }
    }
    freshness { timeToLive = 1.minutes }
}
```

Wrap the blocking call in `withContext(Dispatchers.IO)`, or hand the builder a scope carrying an I/O
dispatcher via `scope(...)`. This is about *your fetcher's* work: the persistence stores already
confine their own I/O to `Dispatchers.IO`. A suspending, non-blocking client (Ktor, OkHttp's
`await` bridge) needs neither.
