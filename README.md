# Aquifer

[![CI](https://github.com/QuasarApps/aquifer/actions/workflows/ci.yml/badge.svg)](https://github.com/QuasarApps/aquifer/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)

**An offline-first, stale-while-revalidate data layer for Kotlin and Android.**

> **JVM & Android today.** Aquifer targets Kotlin/JVM and Android (compiled to `JvmTarget.JVM_11`).
> Kotlin Multiplatform is a post-1.0 goal on the [roadmap](ROADMAP.md), not yet shipped.

Aquifer sits between your remote API and your UI as the single source of truth for keyed data.
You declare *how to fetch* and *how fresh data must be*; Aquifer decides when to serve the
cache, when to hit the network, and keeps every observer of a key in sync — through
process-wide caching, request deduplication, and reactive `Flow` streams.

```kotlin
val users: Aquifer<UserId, User> = aquifer {
    fetcher { id -> api.fetchUser(id) }
    freshness { timeToLive = 5.minutes }
    memoryCache { maxEntries = 256 }
}

// In a ViewModel: render cached data instantly, refresh in the background.
val state: StateFlow<DataState<User>> =
    users.stream(userId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataState.Loading())
```

```kotlin
// In Compose (aquifer-compose): one line, lifecycle-aware, stream shared across recompositions.
val state by users.collectAsState(userId)

state.value?.let { user -> UserProfile(user) }   // keep content visible while refreshing
if (state.isLoading) RefreshIndicator()
(state as? DataState.Failure)?.let { RefreshFailedSnackbar(it.error) }
```

> ⚠️ **Status: pre-1.0.** The API surface is locked by binary-compatibility validation and the
> Maven Central publishing pipeline is configured, but no release has been tagged yet. Once
> `v0.1.0` ships:
>
> ```kotlin
> dependencies {
>     implementation("io.github.quasarapps:aquifer-core:0.1.0")
>     implementation("io.github.quasarapps:aquifer-compose:0.1.0")          // Compose state collection
>     implementation("io.github.quasarapps:aquifer-android:0.1.0")          // reconnect/foreground triggers
>     implementation("io.github.quasarapps:aquifer-persistence-file:0.1.0") // disk persistence
>     implementation("io.github.quasarapps:aquifer-persistence-sqldelight:0.1.0") // queryable persistence
>     implementation("io.github.quasarapps:aquifer-okhttp:0.1.0")            // ETag/304 revalidation
>     testImplementation("io.github.quasarapps:aquifer-test:0.1.0")          // fakeAquifer, FakeClock, settle()
> }
> ```

## Why Aquifer?

Every non-trivial Android app ends up re-implementing the same data plumbing:

- *"Show cached data immediately, then refresh"* — *stale-while-revalidate*, hand-rolled per screen.
- *"Two screens load the same user and fire two identical requests"* — no request deduplication.
- *"The detail screen edited the user but the list still shows stale data"* — no shared source of truth.
- *"The network failed but we have perfectly usable data from an hour ago"* — no stale-if-error fallback.

Aquifer packages those patterns into one small, focused library:

| Capability | What it means |
|---|---|
| **Freshness policies** | `CacheOnly`, `CacheFirst`, `StaleWhileRevalidate`, `NetworkFirst`, `NetworkOnly` — pick per call, not per architecture. |
| **Request deduplication** | Concurrent requests for a key share one in-flight fetch, whether they come from `get` or `stream`. |
| **Reactive streams** | `stream(key)` emits `Loading` / `Content` / `Failure` snapshots and keeps emitting as the key is fetched, written, or invalidated by anyone. |
| **Stale-if-error** | Fetch failed, cache has data? Serve the stale value and surface the error alongside it — never a blank screen. |
| **Caller-proof fetches** | Fetches run in the store's scope: navigating away mid-request doesn't waste the response — it still lands in the cache. |
| **LRU memory cache** | Bounded, thread-safe, TTL-aware. |
| **Deterministic tests** | Inject a `WallClock` and a `CoroutineScope`; staleness and concurrency are fully testable with virtual time. |

Pure Kotlin/JVM with a single dependency (`kotlinx-coroutines`): usable from any Android app
(API 21+; deliberately free of `java.util` methods added in API 24) and from JVM services —
persistence and Android-specific integrations layer on top as separate modules. The optional
`aquifer-persistence-file` module is built on `java.nio.file` and needs API 26+ or NIO
core-library desugaring.

### How it compares

[Store5](https://github.com/MobileNativeFoundation/Store) is the closest neighbour in Kotlin;
TanStack Query and RTK Query are the web patterns most Android teams are borrowing from. What
Aquifer does differently is narrow and specific:

- **Epoch fencing.** Every `put`/`invalidate` bumps a per-key epoch, and every fetch captures its
  epoch *before* it registers, so a response that was already in flight cannot resurrect a value
  you just deleted or edited — it is discarded at commit time instead of racing the mutation.
- **Absence is a state.** `DataState.Empty` is an affirmative "nothing here", distinct from
  loading and from failure, so a `CacheOnly` screen observes a logout-style reset instead of
  rendering deleted data forever.
- **Conditional fetching that survives restarts.** Validators (`ETag`/`Last-Modified`) and a
  server-declared `freshFor` are stored next to the value in the `SourceOfTruth`, so a cold start
  revalidates with a 304 rather than re-downloading.
- **A first-class fake.** `fakeAquifer` is public API in `aquifer-test` — a programmable store with
  assertable fetch counts — not a test-source copy each consumer rewrites.

And the honest losses: Aquifer is **JVM/Android only**, so a data layer shared with iOS is a real
reason to choose Store5 today; it has **no mutation queue** (Store5's
`MutableStore`/`Updater`/`Bookkeeper`, TanStack/RTK's optimistic update with rollback have no
counterpart here yet); and it is **unreleased** — the API is locked by binary-compatibility
validation, but nothing has been published, so none of it has been proven against a real dependent.

## What Aquifer is not

**Read-side only.** `put` is a *local write*, not a pending mutation: there is no rollback, no
retry queue, and no conflict hook. A written value is authoritative until its TTL expires, after
which the first successful, unfenced fetch overwrites it — and the always-fetch strategies
(`NetworkFirst`, `NetworkOnly`, `fresh(key)`) do not wait for the TTL at all, so any of them
replaces the write immediately. That overwrite is observable only as an ordinary fetch — a new
`DataState.Content` and `onFetchSucceeded` — with nothing to say it replaced a local write, and the
write clears the entry's stored validator, so that fetch goes out unconditional. **An offline edit form built
on `put` alone will lose the user's edit once the entry goes stale and the next fetch lands.**
Keep your own outbox until the planned [`aquifer-mutations`](ROADMAP.md) module lands; `put` is
for applying a *confirmed* change (a server push, a response you already have) to the cache.

**Not Kotlin Multiplatform today.** JVM and Android only. KMP is a post-1.0 bet on the
[roadmap](ROADMAP.md) — teams sharing a data layer with iOS should look at Store5.

**Declared non-goals**, so the scope stays honest: image/blob caching (use Coil), cross-process
shared caches, full sync engines/CRDTs, GraphQL response normalization, reflection-based
serialization, and a Java-first API surface.

## Core concepts

### One Aquifer per data family

An `Aquifer<K, V>` manages one kind of data, addressed by key — `Aquifer<UserId, User>`,
`Aquifer<Query, SearchResults>`. Make it a singleton (Hilt/Koin) so every screen shares the
same cache and update bus.

### Freshness decides the cache/network dance

A cached entry is **fresh** until it outlives `timeToLive`, then it's **stale** — still
servable, but due for revalidation:

| Strategy | Fresh entry | Stale entry | Missing entry |
|---|---|---|---|
| `CacheOnly` | cache | cache (stale) | `get` throws, `stream` emits `Empty` |
| `CacheFirst` *(default for `get`)* | cache | fetch → stale on failure | fetch |
| `StaleWhileRevalidate` *(default for `stream`)* | cache | cache, then revalidate | fetch |
| `NetworkFirst` | fetch → cache on failure | fetch → stale on failure | fetch |
| `NetworkOnly` | fetch | fetch | fetch |

> **`timeToLive` defaults to `Duration.INFINITE` — set one.** Staleness is decided by the first
> horizon that applies: a per-call `maxAge`, else the entry's server-declared `freshFor`, else this
> TTL. So with no `freshness { timeToLive = … }` block, any entry carrying *neither* override never
> becomes stale, and for those entries every strategy above collapses onto its *fresh entry* column:
> `CacheFirst` serves the first fetch forever, `StaleWhileRevalidate` never revalidates,
> `revalidateActive()` (and with it `revalidateOnReconnect`/`revalidateOnAppForeground`) refreshes
> only the active keys with nothing cached — a first fetch that failed while offline still retries —
> and `isStale` is permanently `false`. What still reaches the network: a cache miss,
> `NetworkFirst`/`NetworkOnly`, `fresh(key)`, and any entry whose per-call `maxAge` or server-declared
> `freshFor` has elapsed. Give any store whose data can change upstream a `timeToLive`.

Two multi-key divergences are worth knowing before you reach for them. `getAll` is one-shot and
awaits every fetch it triggers, so `StaleWhileRevalidate` there behaves like `CacheFirst` — it
blocks on the network rather than serving stale; use `streamMany` when you want
serve-stale-then-revalidate across many keys. And `maxAge` is a `get`/`stream` knob only:
`getAll`/`streamMany`/`prefetch`/`prefetchAll` take `freshness` alone and follow the store's TTL.

### Streams keep every observer coherent

```kotlin
users.stream(id).collect { state ->
    when (state) {
        is DataState.Loading -> render(state.value, refreshing = true)   // value = previous data, if any
        is DataState.Content -> render(state.value, refreshing = false)  // state.origin: MEMORY / FETCHER / LOCAL
        is DataState.Failure -> renderError(state.error, fallback = state.value)
        is DataState.Empty -> renderEmpty()  // affirmatively nothing: CacheOnly miss or observed deletion
    }
}
```

Updates from *anywhere* — another screen's fetch, a local `put`, an `invalidate` — reach every
active stream of that key. Pull-to-refresh on one screen updates all of them:

```kotlin
suspend fun onPullToRefresh(id: UserId) {
    users.fresh(id)        // forces a fetch; all active streams emit Loading → Content
}

suspend fun onUserEdited(id: UserId, edited: User) {
    users.put(id, edited)  // local write of an already-confirmed change; observers update instantly
}
```

`put` writes to the cache and nothing else — it is not an optimistic mutation, and the next
successful fetch overwrites it without ceremony (after the TTL under the staleness-aware
strategies, immediately under `NetworkFirst`/`NetworkOnly`/`fresh`). See
[What Aquifer is not](#what-aquifer-is-not) before building an offline edit form on it.

### One-shot reads

```kotlin
val user = users.get(id)                            // fresh cache or fetch (CacheFirst)
val current = users.get(id, Freshness.NetworkFirst) // prefer network, tolerate offline
val pinned = users.get(id, Freshness.CacheOnly)     // cache or CacheMissException, never fetches
```

One screen can demand fresher data than the store-wide policy — or accept older — without
changing anything global. `maxAge` overrides the TTL for a single call or stream:

```kotlin
val live = users.get(id, maxAge = 30.seconds)         // refetch unless really fresh
val rough = users.get(id, maxAge = 1.hours)           // old data is fine here, skip the network
val any = users.get(id, maxAge = Duration.INFINITE)   // serve anything cached, fetch only on miss
users.stream(id, maxAge = 1.minutes)                  // this stream's staleness bar, no one else's
```

A fetcher can also declare a per-entry lifetime the origin chose — return
`FetchResult.Fresh(value, validator, freshFor = 5.minutes)` from a `conditionalFetcher` (e.g.
mapped from an HTTP `Cache-Control: max-age`). The precedence, highest first, is **per-call
`maxAge` → server `freshFor` → store-wide `timeToLive`**: an explicit caller bar wins, then the
origin's declared lifetime, then the store default (only the store default is jittered). A
`SourceOfTruth` persists `freshFor`, so it survives restarts; `Duration.ZERO` means "revalidate
on next read", and a `null` (the default) means the origin had no opinion.

Warm what the user is about to open — fire-and-forget, no coroutine needed at the call site:

```kotlin
fun onListItemVisible(id: UserId) {
    users.prefetch(id)   // returns instantly; skips the fetch if already fresh, dedups with reads
}
```

### Many keys, one backend call

A list of 50 items shouldn't mean 50 round-trips. Give the store a `batchFetcher` and `getAll`
collapses the keys that need loading into a single call:

```kotlin
val users = aquifer<UserId, User> {
    batchFetcher { ids -> api.fetchUsers(ids) }   // POST /users?ids=…  ->  Map<UserId, User>
    freshness { timeToLive = 5.minutes }
}

val loaded: Map<UserId, User> = users.getAll(visibleIds)   // fresh keys served from cache; the rest in one call
```

`getAll` returns the **resolved subset** — a key the backend omits, or whose fetch fails, is
simply absent (its error still reaches `AquiferEvents`), so one bad key never sinks the list.
Every per-key guarantee — single-flight dedup, mutation fencing, negative caching,
persistence — holds exactly as for `get`; batching is purely a transport optimization, and an
individual `get(id)` over a `batchFetcher` is just a batch of one.

The reactive and warm-up twins batch the same way: `streamMany(ids)` combines the per-key streams
into one `Flow<Map<UserId, DataState<User>>>` — per-item loading/content/failure coherence, with
the misses fetched in a single call — and `prefetch`'s plural, `prefetchAll(ids)`, warms many keys
in one call, fire-and-forget. A retryable failure of the batch call re-runs the whole batch under
the store's `retry` policy.

```kotlin
val states: Flow<Map<UserId, DataState<User>>> = users.streamMany(visibleIds)  // one batched fetch, per-item state
users.prefetchAll(nextPageIds)                                                 // warm the next page, returns instantly
```

In Compose, `collectAsStateMany(ids)` is the lifecycle-aware binding for that map — one collector for
the whole list or grid (not a per-item collector that restarts on scroll), the multi-key twin of
`collectAsState(key)` (`rememberStreamMany` exposes the raw `Flow`):

```kotlin
val states: Map<UserId, DataState<User>> by users.collectAsStateMany(visibleIds) // one lifecycle-scoped subscription
```

Already holding the data — a websocket frame, a paginated payload? `putAll(entries)` is the
write-side mirror: it seeds many keys in one fenced commit (one update per key), the bulk form of
`put`.

Dropping data is symmetric. `invalidate(key)` evicts one key and `invalidateAll()` wipes
everything; `invalidateWhere { it.startsWith("tenant:") }` is the predicate middle ground — clear a
whole scope in one fenced commit, each matched key fenced exactly like `invalidate`, for
"drop everything for this tenant" resets.

Add a `coalesceWindow` and even *unrelated* `get`/`stream`/`prefetch` calls auto-batch — the
DataLoader pattern, with no change to call sites:

```kotlin
batchFetcher(coalesceWindow = 10.milliseconds) { ids -> api.fetchUsers(ids) }
// 50 cards each call users.collectAsState(id) in one frame -> one POST /users?ids=…
```

Fetches landing within the window collapse into one call (dispatched when the window elapses
or once `maxBatchSize` keys accumulate); a transient failure re-enters the next window.

When the backend speaks ETags, `conditionalBatchFetcher { validators -> … }` composes 304
revalidation with batching: each key arrives mapped to its cached validator and may come back
`NotModified` (kept and re-aged, never re-downloaded) — the batch mirror of `conditionalFetcher`.

```kotlin
conditionalBatchFetcher { validators ->                 // Map<ArticleId, String?>  (id -> ETag)
    api.fetchArticles(validators).mapValues { (_, r) ->
        if (r.status == 304) FetchResult.NotModified else FetchResult.Fresh(r.body, validator = r.etag)
    }
}
```

### Surviving process death

Add a `SourceOfTruth` and cached data outlives the process: cold starts render from disk
instantly, and LRU-evicted entries fall back to storage instead of the network. Timestamps are
persisted too, so time-to-live decisions stay correct across restarts.

```kotlin
val articles = aquifer<ArticleId, Article> {
    fetcher { id -> api.fetchArticle(id) }
    freshness { timeToLive = 10.minutes }
    persistence(
        jsonFileSourceOfTruth(
            directory = context.filesDir.resolve("aquifer/articles").toPath(),
            maxEntries = 500,                // optional: LRU-bound the disk footprint
            maxBytes = 10L * 1024 * 1024,
        ),
    )
}
```

`aquifer-persistence-file` stores one JSON file per key (kotlinx.serialization) with atomic
writes, SHA-256 file naming for arbitrary keys, and self-healing reads that treat corrupt
files as absent. The store is unbounded by default; `maxEntries`/`maxBytes` cap it with
least-recently-used eviction, and temp files orphaned by a crash are cleaned up on first
use. `aquifer-persistence-sqldelight` adds a queryable, enumerable `SourceOfTruth` on SQLDelight,
whose `keys()` backs a disk-wide `invalidateWhere`. Or implement `SourceOfTruth` yourself to back
Aquifer with Room or DataStore — four suspend functions (`read`/`write`/`delete`/`deleteAll`), plus optional
`readAll`/`writeAll`/`deleteMany` to let `getAll`/`streamMany`/`prefetchAll`/`putAll`/`invalidateWhere` batch
in one query or transaction (they default to the per-key loop, so overriding them is purely an
optimization). An enumerable backend may also override `keys()` / `keysWhere(...)`, so a disk-wide
`invalidateWhere` reaches every persisted key — not just those tracked in memory — while the file
store opts out (its SHA-256 filenames are one-way).

Adding a field to your model is safe by default (unknown keys are ignored). For a *breaking*
change — a removed or retyped field — stamp writes with a `schemaVersion` and supply a
`migrate` so old cache files upgrade in place instead of being wiped:

```kotlin
jsonFileSourceOfTruth<UserId, User>(             // User v2: { id, firstName, lastName }
    directory = dir,
    schemaVersion = 2,
    migrate = { fromVersion, value ->            // value is the stored JSON tree
        when (fromVersion) {
            0, 1 -> buildJsonObject {            // v0/v1 stored a single "name"
                val obj = value.jsonObject
                put("id", obj.getValue("id"))
                val parts = obj.getValue("name").jsonPrimitive.content.split(" ", limit = 2)
                put("firstName", parts.first())
                put("lastName", parts.getOrElse(1) { "" })
            }
            else -> null                         // unknown older version: drop and refetch
        }
    },
)
```

Migration runs lazily on read (the entry is rewritten in the new format the next time it's
written) and only for entries below the current `schemaVersion`. Returning `null` drops the
entry — as does one stored at a *higher* version (an app downgrade). A version-0 store (the
default) writes no version field under the default `Json` (`encodeDefaults` off), so opting in is
byte-for-byte the old on-disk format; a caller that supplies `Json { encodeDefaults = true }`
emits `"schemaVersion":0`, as it already does for other defaulted fields.

To keep sensitive values off disk as plaintext, pass a `cipher` — a two-method `ValueCipher`
(`encrypt`/`decrypt`) applied to each entry's serialized bytes. The seam depends on nothing
beyond the JDK, so production crypto plugs in with a thin adapter:

```kotlin
class TinkValueCipher(private val aead: Aead) : ValueCipher {     // Tink + Android Keystore
    override fun encrypt(plaintext: ByteArray, aad: ByteArray) = aead.encrypt(plaintext, aad)
    override fun decrypt(ciphertext: ByteArray, aad: ByteArray) = aead.decrypt(ciphertext, aad)
}

jsonFileSourceOfTruth<UserId, User>(directory = dir, cipher = TinkValueCipher(aead))
```

The on-disk bytes — and the `maxBytes` budget — are the ciphertext, and a `decrypt` that
throws `GeneralSecurityException` (wrong key, tampered file) heals the slot like any other
corrupt entry. The entry's key is passed as authenticated associated data, so a blob copied to
a different key's file on disk is rejected, not served as that key's value. Encryption composes
with bounding, conditional fetching, and schema migration.

### Retries with backoff and jitter

Fetches are not retried by default. Opt in per store:

```kotlin
retry {
    maxAttempts = 3                      // total, including the first attempt
    initialDelay = 250.milliseconds      // then ×2 per attempt, capped at maxDelay
    retryOn = { it is IOException }      // never retries cancellation
}
```

The expiry-side mirror is `freshness { ttlJitter = 0.1 }`: entries fetched together get
deterministically spread expiries (never *later* than `timeToLive`), so a list screen's 50
items don't all revalidate in the same frame.

Retries happen *inside* the shared single-flight fetch: observers see one `Loading` and one
terminal state per cycle, and jitter only ever shortens delays so `maxDelay` is a hard cap.

### Conditional fetching (ETag / 304)

When the backend supports HTTP revalidation, a stale entry doesn't need a re-download to
become fresh again. A *conditional fetcher* receives the cached entry's validator and may
answer `NotModified` — the store keeps the value and restarts its TTL:

```kotlin
val articles = aquifer<ArticleId, Article> {
    conditionalFetcher(
        okHttpConditionalFetcher(                  // aquifer-okhttp wires the headers
            callFactory = client,
            request = { id -> Request.Builder().url("$BASE/articles/$id").build() },
            parse = { _, body -> json.decodeFromString<Article>(body.string()) },
        )
    )
    freshness { timeToLive = 10.minutes }
    persistence(jsonFileSourceOfTruth(dir))        // validators survive restarts too
}
```

Responses' `ETag`/`Last-Modified` headers are captured automatically, replayed as
`If-None-Match`/`If-Modified-Since`, and a 304 re-ages the entry without the payload ever
crossing the network. A non-success status throws `HttpException`, which carries the response
`code` so a retry or negative-cache policy can branch on it (e.g. retry only 5xx). For a
backend without revalidation, `okHttpFetcher` feeds the plain `fetcher` the same way (no
validator, same `HttpException`), and `Call.await()` is public if you'd rather hand-roll one.
No OkHttp at all? Implement the two-line `when` yourself — the
`conditionalFetcher { key, validator -> FetchResult }` contract is transport-agnostic.

Pass `okHttpConditionalFetcher(..., respectCacheControl = true)` to let the origin drive
freshness: a 2xx response's `Cache-Control: max-age` (minus any `Age`) becomes the entry's
[`freshFor`](#freshness-decides-the-cachenetwork-dance), `no-store`/`no-cache`/`max-age=0` mark
it immediately stale, and `Expires` is the fallback. It's off by default, and the precedence is
always per-call `maxAge` → server `freshFor` → store `timeToLive`.

### Negative caching

A failing endpoint shouldn't be hammered by every screen that asks. Opt in per store:

```kotlin
negativeCache {
    timeToLive = 30.seconds      // remember failures this long
    backoffMultiplier = 2.0      // consecutive failures stretch the window
    maxTimeToLive = 5.minutes    // hard cap
    maxEntries = 512             // bound the failure memory (LRU); default 512
}
```

During the window, reads serve cached data when it exists (stale-if-error, without
re-asking the network) and otherwise fail fast with the *remembered* error; new stream
subscribers see it instantly instead of triggering another doomed request. `fresh()` /
`NetworkOnly` still go to the network — an explicit demand is honoured — and any success,
`put`, or `invalidate` clears the memory. The failure memory is LRU-bounded by `maxEntries`,
so an unbounded key space of one-time failures can't grow it forever; evicting a record only
re-permits a fetch of that key (records carry no value, so it never resurrects data).

### Refresh on reconnect (or foreground)

`revalidateActive()` refreshes exactly the keys someone is currently looking at — active
streams — and only if their entries are stale or missing. `aquifer-android` ships the two
triggers every app wants:

```kotlin
users.revalidateOnReconnect(context)   // internet restored (ConnectivityManager-backed)
users.revalidateOnAppForeground()      // app returned to foreground (ProcessLifecycleOwner)
```

Both fire only on genuine offline→online / background→foreground transitions: an
already-online device or already-visible app at subscription never emits, subscribing while
offline or backgrounded emits on the next recovery, and Wi-Fi↔cellular handovers are ignored.
The required `ACCESS_NETWORK_STATE` permission is merged in from the library manifest. For anything custom, `revalidateOn(trigger)` accepts
any `Flow` — a push message, a settings change, a timer.

### Observability

```kotlin
events(object : AquiferEvents<UserId> {
    override fun onFetchFailed(key: UserId, error: Throwable, attempts: Int) =
        Timber.w(error, "fetch failed after $attempts attempts")
    override fun onPersistenceWriteFailed(key: UserId, error: Throwable) =
        analytics.count("cache_write_failed")
})
```

Hooks cover fetch start/success/failure, every retry, and best-effort persistence write
failures. Listeners that throw never disturb the engine.

For a point-in-time peek, `snapshot()` returns the keys currently resident in memory —
non-suspending, never any I/O, safe to call from anywhere (a debug overlay, eviction tuning):

```kotlin
val resident = users.snapshot()        // Set<UserId> in memory right now
debugOverlay.show("cached: ${resident.size}/$maxEntries", resident)
```

It lists memory only — persisted-but-evicted keys aren't included — and returns a stable copy,
not a live view.

`stats()` is the counters companion — hit/miss totals, LRU evictions, and the current in-flight
fetch-registry size, the aggregate numbers the event hooks above can't give you, for hit-rate
dashboards and cache tuning (also non-suspending and I/O-free):

```kotlin
val s = users.stats()
debugOverlay.show("hit rate ${(s.hitRate * 100).toInt()}%  in-flight ${s.inFlight}  evicted ${s.evictions}")
```

A hit is a read satisfied from cache under its requested `Freshness` without awaiting a fetch (a
*fresh* entry for `CacheFirst`, a present one for `CacheOnly`/`StaleWhileRevalidate`); a miss is any
other read — the policy needed a fetch, or `CacheOnly` found nothing (`NetworkFirst`/`NetworkOnly`
always miss). Background revalidation and prefetch warmups aren't counted.

### Answering memory pressure

The memory tier is a cache, not the source of truth, so it's safe to shed under pressure.
`evictMemory()` drops every resident entry; `trimToSize(maxEntries)` keeps the `maxEntries`
most-recently-used and drops the least-recently-used rest. Both are non-suspending, silent, and
memory-only — persistence is untouched, so each dropped key rehydrates from disk (no network fetch,
unchanged staleness) on its next read.

```kotlin
override fun onTrimMemory(level: Int) = when {
    level >= TRIM_MEMORY_COMPLETE -> users.evictMemory()
    level >= TRIM_MEMORY_MODERATE -> users.trimToSize(64)
    else -> Unit
}

override fun onLowMemory() = users.evictMemory()
```

Like `snapshot()`/`stats()` they're safe to call from anywhere, including a closed store, and they
don't disturb active streams, fence in-flight fetches, or count toward `stats().evictions`. On a
store with **no** persistence the memory tier is the only tier, so dropped data is gone and the next
read re-fetches — reach for these on a persistence-backed store.

## Testing your repositories

Aquifer takes time and concurrency as injectable dependencies, so tests are deterministic:

```kotlin
@Test
fun `stale profile is served then revalidated`() = runTest {
    val clock = FakeClock()
    val users = aquifer<String, User> {
        scope(backgroundScope)          // background work runs on the test scheduler
        clock(clock)                    // staleness under your control
        fetcher { fakeApi.user(it) }
        freshness { timeToLive = 5.minutes }
    }

    users.put("u1", cachedUser)
    clock.advanceBy(10.minutes)

    users.stream("u1").test {
        assertEquals(DataState.Content(cachedUser, Origin.MEMORY, isStale = true), awaitItem())
        assertEquals(DataState.Loading(cachedUser), awaitItem())
        assertEquals(DataState.Content(freshUser, Origin.FETCHER, isStale = false), awaitItem())
    }
}
```

The `FakeClock` and `settle()` helpers above ship in **`aquifer-test`** (add it as a
`testImplementation` dependency). That module also provides `fakeAquifer` — a programmable,
in-memory store for unit-testing a repository that depends on an `Aquifer`, without standing up the
real engine. Script per-key values, failures, and delays, then assert how often each key was
fetched:

```kotlin
val users = fakeAquifer<String, User>(backgroundScope) {
    seed("ada" to User("ada"))       // already cached, no fetch
    returns("grace", User("grace"))  // fetched on demand
    failsWith("bad", IOException())  // fetch fails
}

val repo = UserRepository(users)
assertEquals(User("grace"), repo.load("grace"))
assertEquals(1, users.fetchCount("grace")) // assert it fetched, exactly once
```

`fakeAquifer` trades a few of the real engine's behaviors for determinism: it has **no
time-to-live** (a cached value never goes stale on its own, so `maxAge` is validated but inert),
**no single-flight deduplication** (two *concurrent* loads of the same missing key each fetch and
each count), and reports `stats()` as `CacheStats.EMPTY` — assert on `fetchCount`/`fetchedKeys`
instead. Its `evictMemory()`/`trimToSize()` model a single cache tier, so (unlike the real store's
silent shed) they reach active `CacheOnly` collectors and don't keep LRU recency. For TTL,
staleness, single-flight, or shed-around-stream behavior, test against the real store paired with
`FakeClock`.

## Design notes

- **Single-flight fetches.** A per-key registry of in-flight `Deferred`s collapses concurrent
  fetches; the registry cleans up on completion, so memory use is bounded by concurrency, not
  key cardinality.
- **No lost updates.** A stream subscribes to the update bus *before* reading its cache
  snapshot (via `SharedFlow.onSubscription`), so an update can't slip between snapshot and
  subscription.
- **Fetches outlive callers.** Fetches run in the Aquifer's scope, not the caller's: a
  response that arrives after the requesting screen closed still warms the cache for the next
  screen. Cancelling the store's scope (or `close()`) cancels everything.
- **Errors are data.** Streams never terminate on fetch failure; failures are emitted as
  `DataState.Failure` carrying the last known value, and broadcast to all observers of the key.
- **So is absence.** A `CacheOnly` stream of a missing or invalidated key emits
  `DataState.Empty` — an affirmative "nothing here", distinct from loading and from failure —
  so cache-only screens observe logout-style resets instead of rendering deleted data forever.
- **Slow collectors are isolated.** Every stream drains the store's update bus through an
  unbounded per-collector buffer on the store's dispatcher, so one stalled screen can never
  block fetch completion, writes, or other streams.
- **Fencing records are per-key and long-lived.** Each fenced key keeps a small epoch record (a
  key and a `Long`, no value) until `invalidateAll()` clears the set, so a very wide key space on
  a long-lived process — a search or autocomplete store — grows a modest per-key map. Bounding it
  soundly needs an atomic capture across the whole read path, not just the fetch site; until then
  `invalidateAll()` is the mitigation. Tracked as
  [#13](https://github.com/QuasarApps/aquifer/issues/13).

## Contracts

### Exceptions

| Thrown | When |
|---|---|
| `CacheMissException` | `get(key, Freshness.CacheOnly)` with nothing cached. `CacheOnly` *streams* emit `DataState.Empty` instead. |
| `BatchKeyMissingException` | a `batchFetcher` returned a map omitting a requested key. It fails that key alone — the rest of the batch is unaffected. |
| `HttpException` *(aquifer-okhttp)* | an unsuccessful response — neither 2xx nor 304 for `okHttpConditionalFetcher`, any non-2xx for the plain `okHttpFetcher`. Carries `code`/`url` and extends `IOException`, so it flows through `retryOn` and `negativeCache` like any transport failure. |
| `AquiferException` | the base type of `CacheMissException` and `BatchKeyMissingException` (not `HttpException`), and thrown directly to callers awaiting a fetch when the store closes underneath them (never a bare cancellation of the caller's own coroutine). |

Your fetcher's own exceptions are never wrapped: they propagate out of `get`/`fresh` as-is and
arrive in `DataState.Failure.error` unchanged, which is what makes `retryOn = { it is IOException }`
work and lets a `DataState.Failure` consumer discriminate on the concrete type.

### Lifecycle

`close()` cancels in-flight fetches and stops update delivery; cancelling the scope passed to
`scope(...)` does the same, and closing twice is a no-op. Afterwards most members throw
`IllegalStateException`, while the non-suspending memory-only operations — `snapshot()`,
`stats()`, `evictMemory()`, `trimToSize(n)` — stay callable. Streams already collecting simply
stop receiving: they neither complete nor throw, so end them by cancelling the scope that
collects them (`viewModelScope`, `backgroundScope`) rather than waiting on the flow.

### Threading

Fetches and fire-and-forget work (`prefetch`, background revalidation) run under the store's own
`SupervisorJob`, on `Dispatchers.Default` unless you inject a `scope` — so **fetchers execute on the
CPU-sized pool**. One-shot cache reads and writes are *not* moved: `get`/`put` call your
`SourceOfTruth` on the caller's coroutine, so a blocking persistence implementation blocks its
caller and must dispatch itself. A suspending, non-blocking fetcher needs nothing;
a blocking one (`Call.execute()`, JDBC, file reads) must be wrapped, or it will starve the
default dispatcher under load:

```kotlin
fetcher { id -> withContext(Dispatchers.IO) { blockingApi.load(id) } }   // or scope(ioScope)
```

`snapshot()`, `stats()`, `evictMemory()`, and `trimToSize(n)` are non-suspending and I/O-free,
callable from any thread including the main one.

## Try it

```bash
./gradlew :sample:run    # five scenarios: cold start, SWR, local write, process death, reconnect
./gradlew dokkaGenerate  # aggregated API docs in build/dokka/
./gradlew build          # tests + binary-compatibility check (api/*.api dumps)
```

## Roadmap

[ROADMAP.md](ROADMAP.md) is the single ordering of record — what has shipped, what is next
through 1.0 and beyond (KMP, offline mutations, a Paging bridge), and the declared non-goals.
Everything before the first tag sits in its **Now** milestone; the headline is **v0.1.0 on Maven
Central**, which needs the signing secrets, a fix to the release version gate (it checks five of
the seven published modules), and a dated changelog section. See the roadmap for the rest and for
their order — this section deliberately does not restate it.

## Project layout

| Module | Description |
|---|---|
| `aquifer-core` | The store: public API + engine. Pure Kotlin/JVM, depends only on `kotlinx-coroutines-core`. |
| `aquifer-compose` | Jetpack Compose integration: `collectAsState(key)` / `collectAsStateMany(keys)`, `rememberStream` / `rememberStreamMany`, `previewAquifer` (molecule-tested). |
| `aquifer-android` | Android library: `revalidateOnReconnect` / `revalidateOnAppForeground` triggers (Robolectric-tested). |
| `aquifer-persistence-file` | JSON-files `SourceOfTruth` backed by kotlinx.serialization: atomic writes, self-healing reads. |
| `aquifer-persistence-sqldelight` | SQLDelight `SourceOfTruth`: queryable, batched (`IN`-clause + transactions), and enumerable (disk-wide `invalidateWhere`). |
| `aquifer-okhttp` | OkHttp conditional fetching: automatic `ETag`/`Last-Modified` revalidation, 304 → `NotModified`. |
| `aquifer-test` | Test doubles for consumers (`testImplementation`): `fakeAquifer` with assertable fetch counts, `FakeClock`, `settle()`. |
| `sample` | Runnable CLI tour of five scenarios — cold start, stale-while-revalidate, local `put`, process death, reconnect (`./gradlew :sample:run`). |

## License

```
Copyright 2026 Quasar Apps

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
