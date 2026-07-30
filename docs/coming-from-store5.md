# Coming from Store5

For teams evaluating a move from [Store5](https://github.com/MobileNativeFoundation/Store). Store5
is the closest neighbour to Aquifer in Kotlin, and the two solve the same core problem — serve cached
data instantly, refresh intelligently, survive process death.

Store5 API here reflects the [official documentation](https://store.mobilenativefoundation.org/docs/)
as of July 2026. Check it against your version before relying on a snippet.

## Read this first: what has no counterpart

Three of these are structural, not "not yet". Decide on them before you go further.

| Store5 | Aquifer |
|---|---|
| **`MutableStore`, `Updater`, `Bookkeeper`** | **Nothing.** `put` is a local cache write, not a queued mutation — no rollback, no retry queue, no conflict hook, and the next successful fetch overwrites it. If you rely on Store5's write path, stop here. See [*What Aquifer is not*](../README.md#what-aquifer-is-not). |
| **Kotlin Multiplatform** | **JVM and Android only.** Sharing a data layer with iOS is a real reason to stay on Store5. |
| **`Converter` (Network / Local / Output types)** | **One value type.** `Aquifer<K, V>` has a single `V`. Convert inside your `fetcher`, and let your `SourceOfTruth` handle its own serialization. |
| **`SourceOfTruth.reader` returns a `Flow`** | **`read` is a suspend function returning one value.** This one is easy to miss — see [The reactive-storage difference](#the-reactive-storage-difference). |

## Concept mapping

| Store5 | Aquifer |
|---|---|
| `Fetcher.of { key -> … }` | `fetcher { key -> … }` |
| A fetcher returning validators / 304s | `conditionalFetcher { key, validator -> FetchResult }` |
| Batch fetching (hand-rolled) | `batchFetcher { keys -> Map }`, optionally with a coalescing window |
| `SourceOfTruth.of(reader, writer)` | `persistence(SourceOfTruth)` — `read`/`write`/`delete`/`deleteAll` |
| `StoreBuilder.from(…).build()` | `aquifer { … }` |
| `store.stream(StoreReadRequest…)` | `store.stream(key, freshness, maxAge)` |
| `store.get(key)` / `store.fresh(key)` | `store.get(key, freshness)` / `store.fresh(key)` |
| `StoreReadResponse.Data / Loading / Error` | `DataState.Content / Loading / Failure` — plus `Empty` |
| `StoreReadResponseOrigin` | `Origin` (`MEMORY` / `PERSISTENCE` / `FETCHER` / `LOCAL`) |
| `request.fetch` + `shouldSkipCache(…)` flags | one of five named `Freshness` strategies |
| `Validator.isValid(output)` | time-based: `timeToLive`, server `freshFor`, per-call `maxAge` |
| `store.clear(key)` / `clearAll()` | `invalidate(key)` / `invalidateAll()` — plus `invalidateWhere { }` |
| `MemoryPolicy` | `memoryCache { maxEntries }` + `freshness { timeToLive }` |

## Building a store

```kotlin
// Store5
val store = StoreBuilder
    .from(
        fetcher = Fetcher.of { id -> api.fetchUser(id) },
        sourceOfTruth = SourceOfTruth.of(
            reader = { id -> flow { emit(dao.selectById(id)) } },
            writer = { id, user -> dao.insert(user) },
        ),
    )
    .build()

// Aquifer
val users = aquifer<UserId, User> {
    fetcher { id -> api.fetchUser(id) }
    persistence(jsonFileSourceOfTruth(cacheDir))   // or your own SourceOfTruth
    freshness { timeToLive = 5.minutes }           // see the note below — do not skip this
    memoryCache { maxEntries = 256 }
}
```

⚠️ **`timeToLive` defaults to `Duration.INFINITE`.** Store5 treats data as valid only until a
`Validator` says otherwise; Aquifer's default is "cache until told otherwise". An entry with no
per-call `maxAge`, no server-declared `freshFor` and no store `timeToLive` **never goes stale** — so
`CacheFirst` serves the first fetch forever and `StaleWhileRevalidate` never revalidates. Set a
`timeToLive` on any store whose data changes upstream.

## Reading

Store5 composes request flags; Aquifer names the five combinations that matter.

```kotlin
// Store5                                          // Aquifer
StoreReadRequest.cached(key, refresh = false)   ->  Freshness.CacheOnly
StoreReadRequest.cached(key, refresh = true)    ->  Freshness.StaleWhileRevalidate
                                                    // or CacheFirst — see below
StoreReadRequest.fresh(key)                     ->  Freshness.NetworkOnly, or fresh(key)
```

`CacheFirst` and `StaleWhileRevalidate` differ in whether a *stale* hit blocks: `CacheFirst` awaits
the refresh, `StaleWhileRevalidate` serves the stale value immediately and refreshes behind it. Both
serve a *fresh* hit without fetching. Store5's `refresh = true` is closest to
`StaleWhileRevalidate`.

```kotlin
users.stream(id).collect { state ->
    when (state) {
        is DataState.Loading -> render(state.value, refreshing = true)  // previous value, if any
        is DataState.Content -> render(state.value, stale = state.isStale)
        is DataState.Failure -> showError(state.error, fallback = state.value)
        is DataState.Empty   -> renderEmpty()
    }
}
```

Two differences worth knowing:

- **Every state carries the last known value.** `Loading` and `Failure` both expose `value`, so a
  refresh or a failure never blanks the screen. Store5's `NoNewData` has no Aquifer equivalent —
  a 304 is simply not an emission.
- **`DataState.Empty` is new.** It means "affirmatively nothing, and nothing will fetch it", emitted
  to `CacheOnly` streams on a missing key and when a key is invalidated while the stream is live. An
  exhaustive `when` needs the branch.

## Writing and invalidating

```kotlin
// Store5
store.clear(key); store.clearAll()

// Aquifer
users.invalidate(key); users.invalidateAll()
users.invalidateWhere { it.startsWith("tenant:acme:") }   // no Store5 counterpart
```

`invalidateWhere` reaches persisted keys too when your `SourceOfTruth` implements `keys()` — the
SQLDelight adapter does, so the predicate is disk-wide rather than limited to what this process has
touched.

For writes, `put(key, value)` applies a value the server has **already accepted**. It is not
Store5's `MutableStore` write path, and the difference is not cosmetic — read the table at the top.

## The reactive-storage difference

The subtlest migration hazard, because nothing fails loudly.

Store5's `SourceOfTruth.reader` returns a `Flow`, so storage *is* the reactive source: anything that
writes to that database — another feature, a background sync, a different process — causes the reader
to emit and the store to propagate.

Aquifer's `SourceOfTruth.read` is a suspend function returning one value. Streams are fed by
Aquifer's own in-process update bus, so **Aquifer never observes a write it did not make.** An active
stream will not see an external write to the underlying store, and whether a fresh read sees one
depends on whether the key is still resident in memory — so don't rely on either outcome.

If your Store5 setup depends on external writes propagating, you need to tell Aquifer explicitly:

```kotlin
externalChanges.collect { key -> users.invalidate(key) }   // or put(key, value) if you have it
```

## What you gain

- **Epoch fencing.** Every `put`/`invalidate` advances a per-key epoch, and every fetch captures its
  epoch *before* registering, so a response already in flight cannot resurrect a value you just
  deleted — it is discarded at commit time. This is *not* Store5's `Bookkeeper`, which tracks failed
  local mutations for the write path; the two address different problems.
- **Conditional fetching that survives restarts.** Validators and a server-declared `freshFor` are
  persisted next to the value, so a cold start revalidates with a 304 instead of re-downloading.
  `aquifer-okhttp` wires `ETag`/`Last-Modified` for you.
- **Absence as a state.** `DataState.Empty`, above.
- **A first-class fake.** `aquifer-test` publishes `fakeAquifer` with assertable fetch counts, plus
  `FakeClock` and `settle()` — not a test-source copy you rewrite per project.
- **Negative caching, TTL jitter, batch coalescing, `stats()`, memory shedding** — see the
  [README](../README.md).

## Honest status

Aquifer is younger and less proven: Store5 has years of production use across many apps. Weigh that
against the list above, and if the write path or KMP matters to you, Store5 remains the right choice.
