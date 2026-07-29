# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once 1.0 ships; until then minor
versions may contain breaking changes.

## [Unreleased]

### Added

- A `v*` tag now *stages* the Maven Central deployment rather than publishing it outright, leaving
  the Central Portal's Publish button as the last reversible checkpoint before coordinates become
  permanent. A separate, manually dispatched **Cut a GitHub Release** workflow then announces the
  tag from its `CHANGELOG` section, marking a SemVer pre-release suffix as such — kept out of the
  publish run so a release is never announced for a deployment that could still be dropped, and so a
  transient GitHub API failure can never force a re-publish. The tag is also refused outright if the
  CHANGELOG has no section for it, checked before the build so the failure costs nothing.

## [0.1.0] - 2026-07-29

First public release. Aquifer is an offline-first, stale-while-revalidate caching data layer for
Kotlin/JVM and Android: you declare *how to fetch* and *how fresh data must be*, and the store
decides when to serve the cache, when to hit the network, and keeps every observer of a key in
sync. Seven modules publish to Maven Central; the public API of each is locked by binary
compatibility validation.

There is no `Changed` or `Fixed` section below: nothing preceded this release, so everything it
contains is new. Behaviour changes and race fixes made while iterating toward it were never
present in a published artifact and are not release notes.

### Added

**`aquifer-core` — the store**

- `Aquifer<K, V>`, a keyed single source of truth. Reads: `stream(key)`, `streamMany(keys)`,
  `get(key)`, `getAll(keys)`, `fresh(key)`. Writes and invalidation: `put`, `putAll`,
  `invalidate`, `invalidateWhere(predicate)`, `invalidateAll`. Warmup: `prefetch`,
  `prefetchAll`. Revalidation: `revalidateActive()`, `revalidateOn(trigger)`. Introspection:
  `snapshot()`, `stats()`. Memory management: `evictMemory()`, `trimToSize(n)`. Lifecycle:
  `close()`.
- `DataState` stream snapshots — `Loading` / `Content` / `Failure` / `Empty` — that always carry
  the last known value, plus `Origin` (`MEMORY` / `PERSISTENCE` / `FETCHER` / `LOCAL`) and
  `isStale`. `Empty` is an affirmative "nothing here, and nothing will fetch it", emitted to
  `CacheOnly` streams on a missing key and when a key is dropped while the stream is active, so
  cache-only screens observe logout-style resets instead of rendering deleted data forever.
  Extensions: `isLoading`, `valueOrThrow()`, `map`, `onContent`, `onFailure`.
- Five `Freshness` strategies — `CacheOnly`, `CacheFirst`, `StaleWhileRevalidate`, `NetworkFirst`,
  `NetworkOnly` — chosen per call rather than per architecture.
- `aquifer { }` builder DSL: `fetcher`, `freshness`, `memoryCache`, `persistence`, `retry`,
  `negativeCache`, `events`, `clock`, `scope`.
- **Epoch fencing.** Every `put`/`invalidate`/`invalidateAll` advances a per-key epoch, and every
  fetch captures its epoch *before* it registers, so a response already in flight cannot resurrect
  a value that was just deleted or overwrite a newer local write — it is discarded at commit time.
  Persistence hydration is fenced the same way. Post-invalidation stream refetches start genuinely
  new requests rather than joining the doomed one.
- **Per-key single-flight deduplication.** Concurrent `get`/`stream`/`prefetch` of a key share one
  in-flight fetch. Dedup is per-epoch: a mutation during a fetch can briefly overlap two requests
  for one key, by design.
- Fetches run in the store's own scope, so navigating away mid-request still lands the response in
  the cache. Cancelling the injected scope closes the store: awaiting callers get an
  `AquiferException` rather than a silent cancellation of their own coroutine.
- An update bus keeps every active stream of a key coherent, with an unbounded per-collector
  buffer so a stalled collector can never block fetch completion, writes, or other streams. Stream
  ordering is clock-independent — events carry a store-global commit sequence assigned under the
  commit guard, so same-millisecond ties and backwards wall-clock steps can neither reorder nor
  silence updates. Stream startup performs no storage I/O while subscribed to the bus.
- A bounded LRU memory cache with staleness judged against an injectable `WallClock`.
- Exceptions: `AquiferException` (base), `CacheMissException` (thrown by `get(key, CacheOnly)` with
  nothing cached; streams emit `DataState.Empty` instead), and `BatchKeyMissingException` (a batch
  fetcher omitted a requested key — it fails that key alone).
- True API 21 compatibility: no `java.util` methods added in API 24, and every module compiles to
  JVM 11 bytecode.

**Freshness and staleness**

- `freshness { timeToLive }` sets the store-wide entry lifetime. It defaults to
  `Duration.INFINITE` — cache until told otherwise — so a store whose data changes upstream should
  set one.
- A per-call `maxAge` on `get`/`stream` (and Compose's `collectAsState`/`rememberStream`) overrides
  it. Fetch decisions change only for the staleness-aware strategies; `isStale` follows the
  caller's `maxAge` under every strategy.
- `FetchResult.Fresh.freshFor` lets the *origin* declare a per-entry lifetime, persisted alongside
  the value (as `PersistedEntry.serverFreshForMillis`) so the horizon survives process death, and
  carried across a `NotModified` — a 304 re-ages the entry while keeping the lifetime the origin
  last declared.
- Precedence is per-call `maxAge` > server `freshFor` > store `timeToLive`.
- `freshness { ttlJitter }` (in `[0, 1]`) deterministically shortens each entry's effective TTL by
  a factor derived from its key and write timestamp, spreading the expiries of entries fetched
  together — the request-stampede mirror of retry jitter. Shorten-only, stable per entry, and never
  applied to a per-call `maxAge` or to server-declared freshness.

**Fetching**

- `fetcher { key -> value }` for the simple case.
- `conditionalFetcher { key, validator -> FetchResult }` for `ETag`/`Last-Modified` revalidation:
  the fetcher receives the cached entry's opaque validator and may answer `FetchResult.NotModified`,
  keeping the cached value and refreshing its age without the payload crossing the network.
  Validators are persisted, so revalidation stays cheap across restarts. A local `put` stores no
  validator, so the first fetch after a local edit is unconditional by construction.
- `batchFetcher { keys -> Map }` resolves many keys in one backend call — the N+1 cure for list
  screens. `getAll`/`streamMany`/`prefetchAll` dispatch one call through it; a single
  `get`/`stream`/`prefetch` uses it as a batch of one.
- `batchFetcher(coalesceWindow, maxBatchSize) { … }` additionally auto-batches individual fetches
  that land within the window of each other (DataLoader-style) — unchanged call sites, fewer
  round-trips. The batch dispatches when the window elapses or once `maxBatchSize` distinct keys
  accumulate.
- `conditionalBatchFetcher { validators -> Map<K, FetchResult> }` composes the two.
- Configure exactly one of the four. A key absent from a returned map fails only that key; a
  throwing batch fetcher fails the whole batch, which the `retry` policy then re-runs in full,
  firing `onFetchRetried` for every key in it.
- `getAll` returns the **resolved subset** — a per-key failure is omitted rather than thrown, so one
  bad key never sinks a screen — and falls back to cached values on failure.

**Persistence**

- `SourceOfTruth<K, V>`: the persistence SPI. Required `read`/`write`/`delete`/`deleteAll`; optional
  `readAll`/`writeAll`/`deleteMany` (defaulting to per-key loops) so a queryable backend can serve
  a multi-key read in one round-trip; optional `keys()`/`keysWhere(predicate)` for enumeration.
- `PersistedEntry` carries the value, its write timestamp, its validator, and any server-declared
  freshness, so staleness and conditional revalidation both survive process death.
- Hydration on memory misses, best-effort write-through after fetches, and direct mutations that
  become visible in memory and on the update bus only once persistence has accepted them. Bulk
  persistence itself is not transactional unless a store makes it so: the default `writeAll` and
  `deleteMany` loop per key, so a failure partway through can leave an earlier prefix on disk. The
  SQLDelight store overrides both (one transaction); the JSON file store commits its renames under
  one lock acquisition.
- When a store can enumerate, `invalidateWhere` becomes **disk-wide** — its predicate reaches every
  persisted key, not just those tracked in memory this run. A non-enumerable store (the default)
  keeps in-process reach; use `invalidateAll` for a full wipe. The predicate runs outside the commit
  lock, so it must not call back into the store.

**Resilience**

- `retry { }`: opt-in exponential backoff with a hard `maxDelay` cap, delay-shortening jitter, and a
  `retryOn` predicate. Cancellation is never retried. The policy wraps single-key fetches and
  whole-batch calls alike.
- `negativeCache { }`: terminal fetch failures are remembered per key for `timeToLive`, during which
  strategy-driven refetches are suppressed — reads serve a cached value when one exists
  (stale-if-error without re-asking the network) and otherwise fail fast with the remembered error.
  `NetworkOnly`/`fresh()` deliberately bypass it; success, `put`, and `invalidate` clear it.
  Consecutive failures stretch the window by `backoffMultiplier`, capped at `maxTimeToLive`.
  `maxEntries` (default 512) LRU-bounds the failure memory, evicting least-recently-consulted;
  bounding is correctness-neutral, since a negative record carries no value.
- Stale-if-error: a failed fetch with a usable cached value serves the value and surfaces the error
  alongside it, rather than a blank screen.

**Observability and introspection**

- `AquiferEvents<K>`: `onFetchStarted`, `onFetchSucceeded`, `onFetchRetried`, `onFetchFailed`,
  `onFetchSuppressed`, `onPersistenceWriteFailed`, `onRevalidationTriggerFailed`. A failing
  revalidation *sweep* is reported without ending the `revalidateOn` subscription; only a failure of
  the trigger flow itself ends it, and a throwing trigger never escapes as an uncaught exception.
- `snapshot(): Set<K>` — the keys currently resident in memory, as a stable copy.
- `stats(): CacheStats` — `hits`, `misses`, `evictions`, the live `inFlight` gauge, plus derived
  `reads` and `hitRate`. A hit is a caller read satisfied from cache under its requested `Freshness`
  without awaiting a fetch; background revalidation and prefetch warmups aren't counted.
- `evictMemory()` and `trimToSize(n)` shed the in-memory tier, for wiring a long-lived store to
  Android's `onLowMemory()`/`onTrimMemory(level)`. Memory-only: persistence is untouched, so on a
  persistence-backed store each dropped key rehydrates from disk on its next read with no fetch and
  unchanged staleness. On a store with no `persistence`, memory is the only tier — dropped data is
  gone and the next read re-fetches (or yields the empty state under `CacheOnly`).
- `snapshot()`, `stats()`, `evictMemory()` and `trimToSize()` never suspend, never touch
  persistence, and stay callable on a closed store.

**`aquifer-compose`**

- `Aquifer.collectAsState(key, freshness, maxAge)` — lifecycle-aware Compose collection of a key's
  stream, remembered across recompositions, starting from `Loading(null)`.
- `Aquifer.collectAsStateMany(keys, freshness)` — the multi-key counterpart, binding `streamMany` to
  one `State<Map<K, DataState<V>>>`: a single collector for a whole list or grid instead of a
  per-item collector that restarts as items scroll, with the member keys' initial fetches batched
  into one call. Keyed on the `keys` set by value, so an equal set across recompositions reuses the
  stream; the state is an empty map before the first emission.
- `rememberStream(key, …)` / `rememberStreamMany(keys, …)` expose the remembered raw streams for
  custom operators.
- `previewAquifer(vararg entries)` — a fetch-free store for `@Preview`s and UI tests, with live
  `put`/`invalidate` behaviour for interactive previews, and `streamMany` support so multi-key
  previews work with no extra wiring.

**`aquifer-android`**

- `Context.connectivityRestoredFlow()` and `Aquifer.revalidateOnReconnect(context)` — a
  ConnectivityManager-backed offline→online trigger that ignores already-present connectivity and
  Wi-Fi↔cellular handovers. `ACCESS_NETWORK_STATE` is declared in the library manifest.
- `appForegroundedFlow()` and `Aquifer.revalidateOnAppForeground()` — a ProcessLifecycleOwner-backed
  background→foreground trigger that ignores the app launch itself.

**`aquifer-persistence-file`**

- `JsonFileSourceOfTruth` / `jsonFileSourceOfTruth()` — one JSON file per key via
  kotlinx.serialization, with SHA-256 file naming, atomic fsynced writes, self-healing reads for
  corrupt files, and forward-compatible JSON defaults. Implements the full bulk SPI.
- Optional `maxEntries`/`maxBytes` caps enforced by LRU eviction after every write. Recency is exact
  within a process and seeds from file modification times across restarts; a store found over budget
  on first use is trimmed immediately. Unbounded is the default and adds no per-operation
  accounting. Temp files orphaned by a crash mid-write are garbage-collected on first filesystem
  touch.
- `schemaVersion` + `migrate(fromVersion, json)` — a breaking model change no longer means wiping the
  cache directory. Migration runs lazily on read, only for entries stored below the current version,
  and returning `null` drops the entry. A version-0 store (the default) writes no version field
  under the default `Json`, preserving the pre-migration on-disk format byte for byte; a
  caller-supplied `Json { encodeDefaults = true }` emits `"schemaVersion":0`, as it already does for
  the defaulted `validator` field.
- `cipher: ValueCipher?` — encryption at rest. A two-method `encrypt`/`decrypt` seam applied to each
  entry's serialized bytes, depending on nothing beyond the JDK, so production crypto (e.g. Tink's
  `Aead` over the Android Keystore) plugs in through a thin adapter. The entry's key is passed as
  authenticated associated data, so a blob relocated to another key's file is rejected and healed
  rather than served. Composes with bounding, migration, and conditional fetching.
- Requires API 26+ or NIO core-library desugaring (it is built on `java.nio.file`). It cannot
  enumerate keys by design — its filenames are a one-way hash.

**`aquifer-persistence-sqldelight`**

- `SqlDelightSourceOfTruth` — a queryable `SourceOfTruth` over SQLDelight/SQLite. Values stored as
  JSON, keys via a bidirectional codec so the store is **enumerable** and `invalidateWhere` is
  disk-wide. Implements the full bulk SPI (`readAll`/`deleteMany` as a single `IN`-clause statement,
  `writeAll` as one transaction, chunked under SQLite's bound-variable cap). Every operation is
  serialized onto one connection, so the store is safe under the concurrency the SPI allows whatever
  driver you supply. The caller owns the schema lifecycle via the exposed `Schema`.

**`aquifer-okhttp`**

- `okHttpConditionalFetcher(callFactory, request, parse)` — wires conditional fetching
  automatically: captures `ETag`/`Last-Modified`, replays `If-None-Match`/`If-Modified-Since`, and
  maps 304 to `NotModified`.
- `respectCacheControl = true` (default `false`) additionally derives server-declared freshness from
  a 2xx response's cache headers: `max-age` minus `Age`, `no-store`/`no-cache`/`max-age=0` as
  `Duration.ZERO`, and `Expires` against `Date` as a fallback. Unusable headers are absorbed rather
  than failing the fetch; shared-proxy directives are ignored, since this is a private cache.
- `okHttpFetcher(callFactory, request, parse)` — the plain counterpart, for backends that don't
  speak validators.
- `HttpException(code, url)` — a typed failure carrying the status. It *is* an `IOException`, so it
  flows through the normal retry/failure path unchanged, but a policy can now branch on the status:
  `retryOn = { it is HttpException && it.code >= 500 }` retries server errors and lets a `404` fail
  fast. A `404` remains a fetch failure, never a cache miss.
- `Call.await()` — the public suspend bridge both fetchers use: enqueues on OkHttp's dispatcher
  without blocking the caller's thread, cancels the call when the awaiting coroutine is cancelled,
  and closes a response that races in after cancellation. The caller owns the returned `Response`.

**`aquifer-test`**

- `fakeAquifer(scope) { … }` — a programmable, in-memory `Aquifer` for unit-testing repositories
  that depend on one. Script per-key `returns`/`failsWith`/`delays` (or a fallback `fetcher`), `seed`
  a warm cache, then assert `fetchCount()`/`fetchCount(key)`/`fetchedKeys()`; responses can be
  re-scripted at runtime. Deterministic by design: no TTL, no single-flight dedup, and
  `revalidateActive`/`revalidateOn` are no-ops.
- `FakeClock` — a manually advanced `WallClock` for driving staleness in tests of the real store.
- `settle()` — a `TestScope` extension that drains every task scheduled at the current virtual time,
  so fire-and-forget effects can be asserted on. It does not advance virtual time; delay-gated work
  needs `advanceTimeBy(...)`.

### Toolchain

- Kotlin 2.4.10, Gradle 9.5.1, coroutines 1.11.0, serialization 1.11.0, Dokka 2.2.0, AGP 8.13.0,
  Robolectric 4.16.1, molecule 2.2.0. JUnit stays on the 5.x line: JUnit 6 ships JVM-17+ variants
  only, incompatible with the deliberate JVM 11 target.
- detekt 1.23.8 (including the ktlint formatting ruleset) runs on every module as part of
  `check`/`build` with `maxIssues: 0`.
- CI builds and tests on JDK 17 and 21, runs the JVM modules' tests on a real JDK 11 runtime to prove
  the bytecode target, and model-checks the concurrent primitives with Lincheck.

[Unreleased]: https://github.com/QuasarApps/aquifer/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/QuasarApps/aquifer/releases/tag/v0.1.0
