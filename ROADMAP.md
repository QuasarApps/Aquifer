# Aquifer roadmap

The goal: make Aquifer the most *trustworthy* small data layer for Kotlin and Android — the
library you reach for when "show cached data instantly, refresh intelligently, survive
process death, never resurrect deleted data" must actually be true, with every guarantee
tested and every trade-off written down.

**How to read this:** milestones are sorted by importance. *Within* a milestone, **open items come
first, ordered by leverage (impact ÷ effort); shipped items follow in the order they shipped**,
kept as a record rather than re-sorted. Effort: **S** ≈ a day, **M** ≈ a few days, **L** ≈ a
week+, **XL** ≈ multiple weeks. Checked boxes are shipped. A `#N` reference is a GitHub item in
[QuasarApps/aquifer](https://github.com/QuasarApps/aquifer) — an **issue or a pull request**, and
mostly a pull request; the tracked issues are #12, #13, #23 and #29. GitHub redirects
`/issues/N` to `/pull/N` when the number is a PR, so the linked citations resolve either way.

---

## Now — ship v0.1.0 🚢

Everything else compounds once there's a public artifact.

**Where this project actually stands:** roughly six weeks of work, 36 shipped roadmap items, and
a locked public API of 55 types and 284 non-synthetic members across seven
`*.api` dumps — with **zero published artifacts**. Nobody has ever typed
`implementation("io.github.quasarapps:…")` against this library, hit a POM problem, or argued
with a default. Every API decision so far — including the ones about to be frozen at 1.0 — was
made against imagined users, which makes the freeze docket below guesswork until real ones exist.
Shipping 0.1.0 is the highest-leverage remaining action on this file, and the engineering is done:
the version gate, the changelog collapse, the `settle()` correction and the GitHub Release step have
all landed. What remains is owner action — the four signing secrets and the version bump off
`-SNAPSHOT`.

- [ ] **Publish v0.1.0 to Maven Central** — add the four secrets from
  [CONTRIBUTING](CONTRIBUTING.md), bump the (newly single) `version` in `gradle.properties` off
  `-SNAPSHOT`, confirm the `0.1.0` CHANGELOG date still matches the tag date, fast-forward `main` to
  `develop`, push `v0.1.0`. The workflow verifies and *stages* the deployment; publication is a
  deliberate click in the Central Portal, after which the **Cut a GitHub Release** workflow
  announces it. Full walkthrough in [CONTRIBUTING](CONTRIBUTING.md). *(owner action — S)*
- [ ] **Maven Central badge + install snippet verification** after the first release — resolve the
  published coordinates from a clean project, and confirm the snippet still lists all seven
  published modules. *(S)*
- [ ] **"Coming from Store5" migration guide** — pulled forward from 1.0, and the highest-leverage
  adoption asset on this file: the README now names Store5 and states the trade honestly, but
  offers no *mapping*, so the most likely switcher still has nothing to migrate against. The
  mapping is already understood (`Fetcher`→`fetcher`,
  `SourceOfTruth`→`SourceOfTruth`, `StoreRequest`→`Freshness`, `Store.stream`→`stream`,
  `StoreReadResponse`→`DataState`), and the honest differences are short: no
  `MutableStore`/`Updater` yet, JVM/Android only, epoch fencing instead of bookkeeping. An
  afternoon of writing, not the M-sized guide set it was filed as. *(S)*
- [x] **SECURITY.md + issue/PR templates** (shipped) — the *first* release ships an
  encryption-at-rest hook with no private disclosure path, so a report on it would have arrived as a
  public issue. `SECURITY.md` routes reports to GitHub private vulnerability reporting (no invented
  security mailing address; the repo has no published contact) and, more usefully, draws the scope
  line: cached data at rest, the `ValueCipher` key-binding, and validator replay are in scope, while
  plaintext-by-default persistence, SHA-256 filenames (filesystem-safety, *not* confidentiality —
  low-entropy keys are recoverable by hashing candidates), and `put` not being a mutation queue are
  documented behaviour rather than defects. Issue forms ask for the fields that actually diagnose
  this library — the `aquifer { }` block, the module, whether a `timeToLive` was set — and the bug
  form leads with the infinite-TTL default, the single most likely cause of a "why is it not
  fetching" report. Private vulnerability reporting is enabled in repository settings, so the
  `SECURITY.md` link and the issue-template contact link both resolve. *(S)*
- [ ] **Sample Android app, built against the published artifacts** — a small Compose app demoing
  airplane-mode survival, pull-to-refresh coherence, and reconnect revalidation on a device. Moved
  up from 0.5 to sit immediately after the tag, and resolved from Maven Central rather than an
  `includeBuild`, so it doubles as the project's first real consumer: it validates the POMs, the
  coordinates, the install snippet and the Android packaging in one pass. Scoped down from L to
  M — a device demo, not a showcase app. *(M)*
- [x] **Repo hygiene** (shipped) — branching model is settled and now enforced by configuration:
  `develop` is the GitHub **default branch**, so PRs and Dependabot target it without anyone
  remembering to, and `main` is release-only (releases are cut by pushing a `v*` tag). The earlier
  Dependabot toolchain bumps (#7–#11, #19, #20) and the #44/#45 follow-ups are all resolved. The one
  line here that was *not* owner action: `ci.yml` triggered on `push: branches: [main]` plus
  `pull_request`, so a merge landing on `develop` — the branch every PR targets — ran no CI at all,
  and every merge to date landed unverified as a merge commit. `develop` is now in the push
  branches. Concurrency is keyed per *commit* for pushes and per ref for pull requests: scoping
  `cancel-in-progress` to PRs alone would not have been enough, since GitHub keeps only one
  *pending* run per group, so a third merge would cancel the second's queued run and leave that
  commit unverified — the very gap the trigger was added to close.
- [x] **Fence fetches at registration (correctness fix, shipped — #42)** — `refreshWith`
  captured the fetch's epoch in the lazily-started body, which runs *after* `inFlight.putIfAbsent`;
  a `put`/`invalidate` in that gap bumped the epoch but the fetch then read the *post-bump* epoch,
  so its commit passed the epoch check and overwrote the just-written local value — a silent loss
  of a user's write in the headline "never resurrect deleted/edited data" guarantee. Fixed by
  capturing the epoch before `scope.async` (it only ever fails safe), with a deterministic
  register-then-fence interleaving test `MutationFencingTest` didn't cover. *(S)*
- [x] **JDK 17/21 build matrix + JVM-11 runtime check** (shipped — #46) — CI ran only Temurin
  21, while every module compiles to JVM-11 bytecode and CONTRIBUTING promises JDK-17 builds;
  neither was tested, so a newer-API slip or 11-incompatible bytecode could ship undetected.
  Shipped as a `build` matrix over JDK **17 and 21** — not 11, which the Gradle 9 wrapper cannot
  run on — plus a separate `jvm11-runtime` job that installs 11 alongside 21 and uses 11 only as
  the *Test* task launcher. That job covers four modules: `aquifer-core`, `aquifer-test`,
  `aquifer-persistence-file`, `aquifer-okhttp`. **`aquifer-persistence-sqldelight` is excluded**,
  and the two Android modules run under Robolectric on the host JDK, so "runs on a JDK 11
  runtime" is verified for four of the seven modules configured for publication, not all seven. *(S)*
- [x] **Fix the release version gate** (shipped) — `release.yml` verified the tag against five
  hardcoded modules while **seven** declare `publishToMavenCentral()`, so `aquifer-test` and
  `aquifer-persistence-sqldelight` were published by `publishAndReleaseToMavenCentral` without ever
  being checked and either could ship at a version the tag never claimed. The gate now derives its
  list from a root `publishingModules` task, so a module joins the gate the moment it applies the
  plugin, and refuses to release if that list comes back empty rather than passing vacuously. The
  seven copies of `version = "0.1.0-SNAPSHOT"` are hoisted into a single `version` property in
  `gradle.properties`, making a release bump one edit that cannot drift — and leaving the per-module
  check as the guard against a module reintroducing its own. *(S)*
- [x] **Replace `aquifer-test`'s `settle()` with `runCurrent()`** (shipped) — `settle()` was
  `repeat(8) { yield() }`: a fixed hop count standing in for "the scheduler is quiet", under which
  roughly 40 *negative* assertions in the core suite would pass vacuously the day their work needed
  a ninth hop. It is now a `TestScope` extension over `runCurrent()` — draining everything scheduled
  at the current virtual time, complete by construction rather than by hop count — and core's
  same-named `TestHelpers.kt` twin got the identical fix. All 99 call sites compiled unchanged and
  the full suite passed on the first run, so no assertion had yet gone vacuous; the change closes
  the trap before consumers inherit it. The signature change was made while free (nothing
  published); `kotlinx-coroutines-test` joins the module's `api` surface, since the receiver type is
  public API. *(S)*
- [x] **Collapse `[Unreleased]` into a dated `0.1.0` section** (shipped) — the changelog was 27
  separate `### Added` blocks under one `[Unreleased]` heading: a per-PR work log rather than release
  notes, and self-contradictory read end to end (the oldest entry described `DataState` as
  `Loading`/`Content`/`Failure` while a newer one above it added `Empty`). It is now one
  `[0.1.0] - 2026-07-29` section describing the surface as it ships, organised by module, at 246
  lines rather than 543. Every claim was checked against the locked `*.api` dumps rather than carried
  over from the prose. The planned Added/**Changed**/**Fixed** grouping turned out to be wrong for a
  *first* release: those categories are relative to a previous version, and there is none — every
  `Fixed` entry described a race no published artifact ever had, and every "breaking (pre-release)"
  note a delta against an unpublished state. Both are stated as absent and why, and the shipped
  guarantees they described are folded into the surface description. *(S)*
- [x] **Cut a GitHub Release from the tagged CHANGELOG** (shipped) — the release workflow ended at
  `publishAndReleaseToMavenCentral` and held only `contents: read`, so a `v*` tag produced artifacts
  and nothing a watcher could see. It now extracts the tagged `## [x.y.z]` CHANGELOG section and
  creates the release from it. The structure follows from one asymmetry — publishing is
  irreversible, everything around it is retryable: the section is verified **before the build**, so
  a missing one fails while failing is free; and the release is cut outside the publish run, so a
  transient API failure re-runs alone rather than forcing a re-publish that immutable coordinates
  would reject. It began as a `needs: publish` job and became its own manually dispatched workflow
  once publication itself was gated on the Central Portal — chained to a *staged* upload it would
  announce a version nobody can resolve. It holds the only `contents: write`, and every checkout
  uses `persist-credentials: false`. Extraction is a shared script matching the heading
  literally rather than as a regex (a version is not regex-safe), and pre-release detection strips
  SemVer build metadata before looking for a `-`. Uses the runner's `gh`, adding no third-party
  action to the release path. *(S)*

## 0.2 — Compose & everyday ergonomics

What every consuming app touches daily; highest user-facing leverage.

- [ ] **Widen the CLI sample past its first five scenarios** — `sample/…/Main.kt` covers cold
  start, SWR, `put`, "process death" and reconnect-with-retry. It uses no
  batching, no `getAll`/`streamMany`, no `prefetch`, no conditional fetching, no negative caching,
  no `stats`/`snapshot`, no encryption or migration — and nothing that demonstrates single-flight
  dedup. CI runs `:sample:run` once per workflow
  (gated on the JDK-21 matrix leg), so each scenario added is also a free end-to-end smoke test of
  a headline path. *(S)*
- [x] **`aquifer-compose` module** — `Aquifer.collectAsState(key)` built on
  `collectAsStateWithLifecycle`, a `rememberStream` helper, and a `previewAquifer` fake for
  `@Preview`s; behavior-tested with molecule (no UI-test infrastructure). *(M)*
- [x] **`DataState` ergonomics** — `map`, `onContent`/`onFailure`, `valueOrThrow`,
  `isLoading`; pure additive API (`getOrNull` dropped as redundant with `.value`). *(S)*
- [x] **Static analysis in CI** — detekt with the bundled ktlint formatting rules, zero
  tolerated issues, wired into `check`. *(S)*
- [x] **Per-call freshness parameters** — shipped as a `maxAge` parameter on
  `get`/`stream`/`collectAsState` rather than parameterizing the sealed `Freshness` types:
  one orthogonal knob composes with every strategy instead of multiplying sealed variants,
  and the data objects stay simple defaults. Fetch decisions change only for the
  staleness-aware strategies; a stream's `isStale` flags follow the caller's bar under
  every strategy, and `Duration.INFINITE` means "serve anything cached". (The multi-key entry
  points did *not* get the knob — see the 1.0 freeze docket, where that asymmetry becomes
  permanent.) *(M)*
- [x] **Bounded disk store** — `maxEntries`/`maxBytes` LRU eviction on
  `JsonFileSourceOfTruth` plus orphaned-temp-file GC on first use. Shipped with an
  in-memory access-ordered index (exact within a process) seeded from file mtimes across
  restarts — no index file to keep crash-consistent — and an absolute byte cap
  (DiskLruCache-style: an entry exceeding `maxBytes` alone is not retained). *(M)*
- [x] **Multi-key Compose binding** (shipped) — `collectAsStateMany(keys): State<Map<K, DataState<V>>>`
  and `rememberStreamMany`, the Compose counterparts to the shipped `streamMany`/`getAll`: one
  lifecycle-aware collector for a list or grid screen instead of a per-item collector that restarts
  on scroll. Distinct-named (like `stream`/`streamMany`), remembered per `(aquifer, keys, freshness)`
  and keyed on the `keys` set by value; empty map before the first emission; no `maxAge` knob
  (mirroring `streamMany`). `previewAquifer` already backs `streamMany`, so multi-key `@Preview`s
  need no extra wiring. The lighter, in-scope half of multi-key support — distinct from the deferred
  Paging bridge. *(M)*
- [x] **`DataState.Empty` / observable deletion** — designed in RFC [#23](https://github.com/QuasarApps/aquifer/issues/23), shipped as a new
  sealed member emitted only to `CacheOnly` streams (initial miss and observed
  `invalidate`/`invalidateAll`); fetch-capable streams keep signalling through their
  refetch. Replaces the dishonest `Failure(CacheMissException)` miss emission;
  source-breaking for exhaustive `when`s, taken deliberately pre-1.0. *(M)*

## 0.3 — Network efficiency & resilience

Make the fetch path cheap and stampede-proof under real-world conditions.

- [ ] **Fix `revalidateActive()` — batch it, honor per-stream `maxAge`, add a force knob** — the
  reconnect path walks `activeKeys` and does a per-key `load()` then `refresh()`. `load()` returns
  from memory first, so the storage hit is per *non-resident* active key rather than per key — but
  on the cold reconnect that matters (process resumed, memory shed) that is still N sequential
  reads where one `readAll` would do. On the fetch side the sweep's per-key `refresh()` calls are
  merged only by the single accumulator, which exists solely when a plain `batchFetcher` is paired
  with a positive `coalesceWindow`; `fetcher`, `conditionalFetcher`, `conditionalBatchFetcher` and a
  window-less `batchFetcher` all stay N calls, so the gap is every store without that window. It
  also cannot see per-stream freshness — `activeKeys`
  is a bare `ConcurrentHashMap<K, Int>` refcount — so a `stream(key, maxAge = 30.seconds)` is
  revalidated against the store-wide TTL instead of the bar its caller asked for. And under the
  **default** `timeToLive = Duration.INFINITE` an entry carrying no server-declared `freshFor` is
  never expired, so a store built with no `freshness { }` block revalidates *only the active keys
  with nothing cached, plus any whose server horizon has elapsed* on reconnect, while looking like
  it refreshes everything on screen.
  Batch the staleness check and the refresh through the bulk SPI, carry each registration's
  `maxAge` alongside the refcount, and add an explicit "refresh every active key regardless of
  staleness" escape hatch — pull-to-refresh has no way to express that today. *(M)*
- [ ] **Decide what a local `put` does to the validator** — `put`/`putAll` write
  `PersistedEntry(value, now)`, silently dropping the entry's `validator` and
  `serverFreshForMillis`. So a locally written key loses its ETag and its next conditional fetch
  goes out unconditional: a full-body download where a 304 was available, on precisely the keys an
  app edits most. Dropping is *defensible* — a 304 against a stale validator would re-age a
  locally modified value as though the server had confirmed it. The drop is now documented (in
  `put`/`putAll`'s KDoc and the README's *What Aquifer is not*); what remains is the design call:
  keep dropping it, or retain the validator behind a "locally modified" marker that suppresses the
  re-age so an edited key can still take a 304. *(S)*
- [ ] **[#12](https://github.com/QuasarApps/aquifer/issues/12) — benchmark, then stripe the commit guard** *(deferred)* — a JMH-style harness for
  concurrent commit throughput against a real file store, then per-key lock striping only if the
  numbers justify it (constraints documented in the issue). Deferred for two reasons: with zero
  published artifacts there is no real workload to benchmark, so any harness written now encodes a
  guess about contention nobody has reported; and the hydration-guard fix queued in 0.5 changes how
  much traffic the commit lock actually sees, so a baseline taken today measures a shape that is
  about to move. Revisit after 0.1.0 has users, or the first time someone reports commit
  contention. *(M–L)*
- [x] **Conditional fetching (ETag / Last-Modified)** — shipped as
  `conditionalFetcher { key, validator -> FetchResult }` with `Fresh(value, validator)` /
  `NotModified`: validators are stored next to the value (memory, `PersistedEntry`, the
  JSON file store) and a 304 re-ages the entry under normal epoch fencing instead of
  re-downloading. `aquifer-okhttp` ships `okHttpConditionalFetcher` for automatic
  `ETag`/`If-None-Match` + `Last-Modified`/`If-Modified-Since` wiring. Plain fetchers keep
  their exact pre-existing path. *(L)*
- [x] **Negative caching** — shipped as opt-in `negativeCache { timeToLive,
  backoffMultiplier, maxTimeToLive }`: terminal failures suppress strategy-driven refetches
  for a per-key window (stale values still served, valueless reads fail fast with the
  remembered error), `NetworkOnly` bypasses, success/mutation clears, and the
  consecutive-failure streak — which survives window expiry — stretches the window.
  Suppressions are observable via `onFetchSuppressed`. *(M)*
- [x] **TTL jitter** — shipped as `freshness { ttlJitter = 0.1 }`: shorten-only (the
  configured TTL stays the hard cap, mirroring retry jitter's rule), with each entry's
  factor derived deterministically from its key and write timestamp — same-tick bursts
  spread, no fresh/stale flicker, nothing extra persisted, and restart-stable for keys
  with value-based `hashCode`s. `maxAge` overrides stay
  exact. *(S)*
- [x] **`prefetch(key)`** — shipped as `fun prefetch(key, freshness = CacheFirst)`:
  fire-and-forget warmup that returns immediately, honours the freshness fetch decision
  (a fresh entry triggers nothing), shares the single-flight fetch with concurrent
  reads, stands down under negative caching, and never throws (failures surface through
  events). *(S)*
- [x] **Batched fetching, phase 1** (RFC [#29](https://github.com/QuasarApps/aquifer/issues/29)) — a `batchFetcher { keys -> Map }` builder
  option and `getAll(keys, freshness)` that collapses N keys into one backend call, reusing
  the per-key machinery (single-flight, fencing, negative caching, persistence, events) so
  batching is a pure transport optimization; a single `get`/`stream`/`prefetch` is a batch of
  one. Returns the resolved subset (per-key failures omitted, not thrown). *(L)*
- [x] **Batched fetching, phase 2 — coalescing window** (RFC #29) — the DataLoader window
  (`batchFetcher(coalesceWindow, maxBatchSize)`) that auto-batches individual
  `get`/`stream`/`prefetch` fetches: window/size-triggered flush, same-key slot sharing, and
  retry that re-enters the next window. *(M)*
- [x] **Batched fetching, phase 2 — remaining** (RFC #29) — shipped `streamMany(keys)` (the
  reactive twin of `getAll`, with the member keys' initial fetches batched into one immediate
  call), `prefetchAll(keys)` (the fire-and-forget batch warmup), and whole-batch retry: the store
  `retry` policy now wraps the multi-key `batchFetcher` call (retry-all — a partial map's omitted
  keys are definitive misses, never a retried slice), firing `onFetchRetried` per key and
  reporting the batch's attempt count to each key's `onFetchFailed`. Completes RFC #29 phase 2. *(M)*
- [x] **Conditional batch fetching** — shipped as
  `conditionalBatchFetcher { validators -> Map<K, FetchResult> }`, the batch mirror of
  `conditionalFetcher`: each key arrives mapped to its cached validator and may answer
  `NotModified`, so ETag/304 composes with `getAll`/`streamMany`/`prefetchAll` (and batch-of-one
  single reads). Whole-batch retry, per-key miss (`BatchKeyMissingException`), and the
  `NotModified`-without-validator contract violation all carry over; the auto-coalescing window
  stays `batchFetcher`-only. *(M)*
- [x] **Typed OkHttp errors** (shipped — #48) — `okHttpConditionalFetcher` collapsed every
  non-2xx/304 into a bare `IOException` whose only status signal was the message string, so
  `retry`'s `retryOn` predicate and `negativeCache` branched blind to status: a permanent 404
  burned every retry attempt. Now throws a typed `HttpException(code, url)` (still an
  `IOException`, so it flows through the normal failure path) that callers can branch on to route
  404 → empty, retry only 5xx, etc. *(S)*
- [x] **Plain `okHttpFetcher` + public `Call.await` seam** (shipped — #49) — a non-conditional
  OkHttp fetcher for backends without ETag/Last-Modified validators, plus the suspend
  `Call.await` bridge promoted to public API, so a plain JSON-over-OkHttp fetcher no longer has
  to be hand-rolled. *(S)*
- [x] **Cache-Control-aware freshness** (design first; shipped — #50, #51) — an origin's
  `Cache-Control`/`Expires` can inform the staleness decision under an explicit precedence
  (per-call `maxAge` > server `freshFor` > builder `timeToLive`; server freshness is never
  jittered). Core seam #50 added `FetchResult.Fresh(value, validator, freshFor)` — stored next to
  the value, carried forward across a 304, persisted in `PersistedEntry`/the JSON file store;
  #51 wired `okHttpConditionalFetcher(respectCacheControl = true)` to parse `max-age` (minus
  `Age`), `no-store`/`no-cache`/`max-age=0` → immediately stale, and `Expires` as a fallback.
  Opt-in, so *the app declares how fresh data must be* stays the default stance. *(M)*

## 0.4 — Persistence expansion

Meet apps where their storage already is. The two SPI capabilities came first: both adapters'
whole point (native batched transactions, a disk-wide `invalidateWhere`) is inexpressible
through a single-key `SourceOfTruth`, so building the adapters first would either hardcode
N-round-trip behavior or force a contract break mid-milestone.

- [ ] **Proto DataStore adapter** (`aquifer-persistence-datastore`) *(deferred)* — the two SPI
  capabilities it was sequenced behind have both shipped, and the SQLDelight adapter already proves
  out the queryable/enumerable case, so what remains is the shape Proto DataStore is *worst* at: it
  keeps one protobuf blob per instance, so a keyed cache becomes a single map-valued message that
  every single-key write rewrites and fsyncs whole — the opposite of the bounded, per-key LRU the
  file store gives you — and it needs a generated `Serializer<T>` per value type, which neither
  JSON-based store requires. Real cost, no capability the two existing adapters lack. Deferred
  until someone asks with a concrete app; "it's the modern AndroidX default" is not a use
  case. *(M)*
- [x] **Bulk `SourceOfTruth` capability** (shipped — #53/#54/#55) — optional `readAll(keys)` / `writeAll(entries)` /
  `deleteMany(keys)` on the SPI, defaulting to the current per-key loop. Before it, `getAll`,
  `putAll`, and `invalidateWhere` did N storage round-trips (per-key `read`/`write`/`delete`),
  and a queryable backend could not express its native batched transaction or `IN` query through
  the four single-key methods. Default implementations keep the JSON file store and existing
  custom stores source-compatible, and make the already-shipped batch paths batch at the
  storage layer too. **Prerequisite for the adapters below.** *(M)*
- [x] **Key-enumeration capability** (shipped) — an opt-in `keys()` / `keysWhere(...)` seam so a
  queryable store backs a *disk-wide* `invalidateWhere` instead of an in-process-keys-only
  predicate. Default returns `null` (opt out): the JSON file store opts out by design, as its
  filenames are one-way SHA-256 of the key, so enumeration would demand a separate key→hash
  manifest with its own crash-consistency story — exactly what the LRU index deliberately avoids.
  **Shaped the adapters below, and unblocked tag/group invalidation in 0.6.** *(L)*
- [x] **SQLDelight adapter** (shipped) — `aquifer-persistence-sqldelight`: queryable persistence
  whose enumerability backs a correct disk-wide `invalidateWhere`, and the natural stepping stone
  to multiplatform. Values as JSON, keys via a bidirectional codec; bulk via `IN`-clause +
  transaction; generated DB classes kept out of the locked public API. *(M)*
- [x] **Encryption hook** — shipped as a `cipher: ValueCipher?` on `JsonFileSourceOfTruth`: a
  two-method `encrypt`/`decrypt` seam applied to each entry's serialized bytes, depending on
  nothing beyond the JDK so Google Tink's `Aead` (Android Keystore) plugs in through a thin
  adapter. The on-disk bytes and the `maxBytes` budget are the ciphertext; a `decrypt` that
  throws `GeneralSecurityException` heals the slot. Composes with bounding, conditional
  fetching, and `schemaVersion`/`migrate`. *(M)*
- [x] **Schema-migration helper** — shipped on `JsonFileSourceOfTruth` as `schemaVersion` +
  `migrate(fromVersion, value)`: writes are stamped with the current version, and an entry read
  back at a lower version is passed to the callback to rewrite its stored JSON to the current
  shape (lazily on read; rewritten in the new format on the next write). Returning `null` drops
  the entry — as does one stored above the current version (an app downgrade) — so breaking
  model changes stop meaning "wipe the cache directory". A version-0 store (the default) writes
  no version field: byte-for-byte the previous on-disk format. *(M)*

## 0.5 — Proof-grade quality & observability

The engine's guarantees deserve machine-checked evidence.

- [ ] **Robolectric multi-SDK config** — `Connectivity.isCurrentlyOnline()` uses the deprecated
  `allNetworks` because its replacement needs API 23 while `minSdk` is 21, and both Android test
  classes pin `@Config(sdk = [35])`. So the compatibility branch is exercised *only* at the API
  level where it is deprecated and never at the ones it exists for, and `minSdk = 21` is a promise
  the suite does not keep. Run the connectivity tests across a low/high SDK pair. *(S)*
- [ ] **Key the hydration guard on a commit-only generation counter** — the guard shipped in 0.6
  captures `sequencer.get()` before the off-lock persistence read and re-reads under `commitGuard`
  if it moved. But hydration *itself* advances the sequencer (`load`/`loadAll` allocate a sequence
  for the entry they install), so the guard fires on far more than "a commit raced": two concurrent
  cold reads of *different* keys interfere: the first to take the lock sees an unchanged sequencer
  and hydrates directly, but its own sequence allocation forces every later contender through the
  guarded re-read, so N concurrent cold reads cost N−1 extra reads — each performed while holding
  the commit lock, and for `loadAll` that re-read is the whole batch again
  (`store.readAll(epochs.keys)`), which is exactly the shape of a cold start. Correctness is
  unaffected (the re-read is authoritative); the cost is avoidable I/O in the window where the
  commit lock is most contended. The invariant the guard actually needs is "no *commit*
  intervened", so give commits their own counter that hydration does not advance. *(M)*
- [ ] **Point Lincheck at the concurrency that is actually hand-rolled** — two of the shipped
  classes prove very little for their cost: `MemoryCacheLincheckTest` and
  `BoundedLruMapLincheckTest` run `maxEntries = 10` against keys `1:3`, so eviction never fires,
  over operation bodies that are one `synchronized` block each. They are not *incapable* of
  failing — they would catch a `synchronized` being dropped or split wrongly — but that is the
  whole of their guarantee. (`MemoryCacheEvictionLincheckTest` already covers the interesting half
  for `MemoryCache`; `BoundedLruMap` has no eviction counterpart at all.) Meanwhile the code that
  *is* hand-rolled has no model-checking: `EpochFence.fence` does `keyEpochs[key] =
  (keyEpochs[key] ?: 0L) + 1L` — a non-atomic read-modify-write on a `ConcurrentHashMap`, correct
  today only because every one of its four call sites happens to hold `commitGuard`, an invariant
  nothing states or enforces — and `registerActive`/`unregisterActive` are hand-written CAS loops
  over a refcount map. Keep the baseline classes for their
  synchronization-removal coverage and add cases aimed at those two primitives.
  **And reopen the fencing half with the corrected framing:** the shipped item is right that epoch
  fencing is not a *linearizability* property, but that indicts the linearizability **verifier**,
  not Lincheck. The model-checking **strategy** with an `EpsilonVerifier` (accept any result) plus
  a `@Validate` invariant is a bounded interleaving explorer, and "a committed fetch never
  overwrites a mutation that began after it" is exactly the kind of invariant `@Validate` asserts.
  The #42 class of bug is reachable that way. *(M)*
- [ ] **Docs site** — `dokkaGenerate` already runs in CI as a compile check; only the GitHub
  Pages deploy step is missing. Publish the aggregated HTML on release for a versioned, browsable
  API reference. *(S)*
- [ ] **Minimal `androidTest` smoke suite** — there is no `androidTest` source set anywhere in the
  repo: `aquifer-android` and `aquifer-compose` are verified entirely by Robolectric on the host
  JVM, against shadows that approximate `ConnectivityManager` rather than implement it. One
  instrumented test per module — a real connectivity transition driving `revalidateOnReconnect`,
  and one `collectAsState` recomposition on an emulator — covers what shadows cannot: real platform
  callbacks, manifest merging, and lifecycle behaviour. Note it does *not* cover R8 by default,
  since instrumented tests run a debug variant and neither Android module configures a minified test
  variant; keeping/uncovering consumer rules needs an explicit minified-release check. *(M)*
- [ ] **Prefer mutation testing to a line-coverage gate** — a coverage threshold scores a test
  that executes a branch and asserts nothing as fully covered, precisely the failure mode this
  suite carried until the yield-bounded `settle()` was replaced (its negative assertions ran the
  code and could assert vacuously), and the class of gap a percentage can reintroduce silently. Mutation testing over `aquifer-core`'s fencing, eviction and
  negative-cache branches answers the question a percentage only gestures at — *if this line were
  wrong, would a test fail?* Slower to adopt and noisier on Kotlin bytecode, hence M, and it needs
  a baseline run before it can gate anything. *(M)*
- [ ] **Coverage gate** *(demoted — see above)* — Kover + a CI threshold + badge. Still cheap
  and still worth the badge once there is an artifact to badge, but it produces a number rather
  than evidence, so it ranks below mutation testing rather than above it. Worth doing on its own only
  if the mutation-testing item stalls. *(S)*
- [ ] **[#13](https://github.com/QuasarApps/aquifer/issues/13) — bounded `keyEpochs`** *(deferred)* — the live-fetch refcount
  sketched in the issue is **necessary but insufficient**: it covers only the fetch capture site,
  while `load`/`loadAll`/stream-preload also capture a `(globalEpoch, 0)` snapshot on off-lock,
  non-fetch paths, and even the fetch capture races its own refcount increment. Any fetch-scoped
  refcount looks correct against today's (fetch-only) interleaving tests yet silently un-fences the
  hydration and stream paths — a subtly-wrong break of the crown-jewel "never resurrect deleted
  data" guarantee, which the issue rates worse than the leak. A sound eviction needs an
  atomic-capture protocol across the hot read path; now that the epoch/registry primitives are
  extracted (`EpochFence`), that protocol has a clear home, and the reopened Lincheck item above
  gives it a way to be checked rather than only hand-interleaved. Still deferred until that harness
  exists. The `invalidateWhere` growth vector is no longer hypothetical: a disk-wide sweep over an
  enumerable store fences every matched **disk-only** key too, so it can add `keyEpochs` entries in
  proportion to the matched set, held until `invalidateAll`. *(M)*
- [x] **Lincheck concurrency tests** (data structures shipped; fencing scoped out at the time —
  now reopened above) — model-check the engine's invariants instead of relying on hand-written
  interleavings. Lincheck 2.39 on a JDK-21 runner, isolated in a dedicated `lincheckTest` task + CI
  job so slow model-checking stays out of `check`/`build`. **Shipped:** linearizability of
  `MemoryCache` (with and without eviction), the negative cache's `BoundedLruMap`, the real
  engine's fetch-free mutation region
  (`put`/`invalidate`/`invalidateAll`/`get(CacheOnly)` under `commitGuard`, doubling as the canary that
  `suspend` `@Operation`s + a coroutine `Mutex` are Lincheck-schedulable), and — after extracting the
  epoch/registry primitives from `RealAquifer` (extraction-with-delegation, so the checked code *is* the
  production code) — the `SingleFlightRegistry` fetch-dedup registry.
  **Scoped out at the time (the erstwhile "flagship #42" part):** epoch fencing is a **real-time
  (happens-before) property, not a linearizability one** — a fetch's commit must take effect as of
  its *start* (before a racing `put`), which linearizability never forces: it may reorder the
  overlapping commit after the mutation, and the #42 regression is *sequentially* consistent, so a
  linearizability **verifier** cannot flag it. That reasoning holds and is why fencing (#42) and the
  residual hydration race stay on the deterministic interleaving tests (`MutationFencingTest`,
  `FenceDuringRegistrationTest`) today. What it does *not* establish — and the shipped note
  overstated — is that Lincheck as a whole is the wrong tool: its model-checking strategy under an
  `EpsilonVerifier` is an interleaving explorer, not a linearizability check. Reopened above. *(L)*
- [x] **Bound the negative-cache map** (shipped) — the `negative` map had the same unbounded-growth
  lifecycle (a wide key space of one-time failures — a search/autocomplete store hitting transient
  5xx — retained a record per key until `invalidateAll`), but bounding it is **sound and independent**
  of the `keyEpochs` proof: a negative record carries no value, so evicting one can only re-permit an
  already-epoch-fenced fetch (a QoS/backoff regression, never a resurrection). Shipped as
  `negativeCache { maxEntries }` (default 512) over an access-ordered `BoundedLruMap` with inline LRU
  eviction under the map's own monitor (independent of `commitGuard`); uniform order, *not*
  expired-first (which would erase the failure streak the map preserves). *(S)*
- [x] **`stats()` snapshot API** — shipped as `stats(): CacheStats`: non-suspending per-store
  counters (hits, misses, evictions, in-flight gauge, plus derived reads/hitRate), the numbers
  `AquiferEvents` can't aggregate. Counted at the caller-read chokepoints (get/getAll/stream
  prime); background revalidation and prefetch warmups are excluded. *(S)*
- [x] **`aquifer-test` module** — shipped: a published, programmable fake `Aquifer`
  (`fakeAquifer(scope) { … }` with scripted values/failures/delays and assertable fetch counts,
  re-scriptable at runtime) plus the deterministic `FakeClock` and the `settle()` helper, so
  consuming apps can unit-test their repositories the way this library tests itself — the
  unit-test sibling of `previewAquifer`. (`settle()` has since become a `TestScope` extension over
  `runCurrent()` — see Now; and the fake implements the fully abstract `Aquifer` interface, which is
  what makes the interface's implementation stance a 1.0 decision.) *(M)*
- [x] **`streamMany` scale ceiling — documented, then characterized** (shipped — #59) —
  `streamMany` opens one bus-collector coroutine (each with an unbounded buffer) per member and
  rebuilds the whole result `Map` on every per-key change: O(N) work per emission and O(N) live
  buffers, and a member set larger than `memoryCache.maxEntries` (default 512) thrashes the LRU.
  Shipped a `### Scale` KDoc section documenting the interaction and that it is *not* a paging
  replacement (pointing at the planned `aquifer-paging`), plus two characterization tests over member
  sets larger than the cache cap: at 3× the cap every member still resolves in one batch round-trip,
  and (3 keys in a 2-slot cache) an evicted key's stream still observes writes via the event bus. A
  soft cap / chunked emission was evaluated and deferred — no
  logging seam exists, the right threshold is caller-dependent, and docs + characterization let
  callers decide; a cap can follow a concrete request. *(M)*
- [x] **Docs-accuracy pass** (shipped) — reconciled the inconsistencies the project review
  surfaced: linked the primary RFC/issue citations to their GitHub items for one-click navigation;
  surfaced `fakeAquifer`'s deliberate
  divergences (no TTL, no single-flight dedup, `CacheStats.EMPTY`) in the README testing section;
  and added a prominent "JVM/Android today" note near the top of the README. Two review items were
  found already-accurate on inspection and left as-is: the `collectAsState` initial state is
  consistently `Loading(null)` (README/CHANGELOG/KDoc; the multi-key `collectAsStateMany` starts
  from an empty map *by design*), and the `TestHelpers.kt` reference in CONTRIBUTING is **not**
  stale — that file still exists as `aquifer-core`'s internal test helper, distinct from the
  *published* `settle()`/`FakeClock` twins in `aquifer-test` (the README testing section already
  points consumers there). *(S)*

## 0.6 — API ergonomics & polish

Small, high-frequency conveniences surfaced while building the feature set; each must keep
the existing fencing and single-flight guarantees.

- [ ] **Tag/group invalidation** — an opt-in tag index so a write can drop every key carrying a
  tag without the caller enumerating them — the relationship-invalidation ergonomic TanStack
  (key patterns) and RTK Query (`providesTags`/`invalidatesTags`) make first-class. Strictly a
  tag index, **not** response normalization (a declared non-goal). **Unblocked:** this was
  sequenced after the key-enumeration capability so it could be disk-correct rather than inheriting
  an in-process-only caveat — that capability shipped in 0.4, and `invalidateWhere` is already
  disk-wide on an enumerable store. The open design question is now just where the index lives:
  in-process only (cheap, lost on process death) or persisted next to the entries (survives
  restart, needs its own crash-consistency story). *(M)*
- [ ] **Key-scoped policy resolver** *(deferred)* — let one store apply heterogeneous TTL (and
  later retry/negative-cache) by key subtype — `freshness { timeToLiveFor = { key -> … } }` —
  instead of spinning up a separate `Aquifer` per policy (which duplicates the memory cache, scope,
  and persistence wiring). Per-call `maxAge` already covers the read-time staleness bar; the
  genuine gap is per-key retry/jitter/negative-cache. Deferred, not dropped: it is L-sized *new
  public surface* on an API no external user has exercised yet, and the resolver's shape (which
  axes are per-key, whether it resolves once or per call) is exactly the kind of thing a 1.0 freeze
  makes permanent. Revisit once 0.1.0 has users who can name the axis they need. *(L)*
- [x] **`evictMemory()` / `trimToSize(n)`** (shipped) — shed the in-memory tier without touching
  persistence (dropped keys rehydrate from disk on the next read), so a long-lived store can answer
  Android's `onLowMemory`/`onTrimMemory(level)`. Non-suspending, silent (no events, no fencing, no
  epoch bump), memory-only, safe on a closed store — like `snapshot`/`stats`; manual shedding is not
  counted in `CacheStats.evictions`. Shipping these required one prerequisite fix in the same change:
  `commitFetched` now persists *before* bumping the sequencer (matching every other writer), which
  the hydration guard's "sequence *S* observed ⇒ disk at *S*" invariant depends on — otherwise an
  eviction dropping a just-committed entry mid-persist would let a racing `load` serve its stale
  pre-commit snapshot (mutation-verified regression test). An optional proactive memory-TTL sweep is
  a separate companion. *(M)*
- [x] **Close the `load()`/`loadAll()` residual hydration race** (shipped) — `load` reads persistence
  *outside* `commitGuard` and re-checks only memory under the lock; since fetch commits don't move the
  epoch, that memory re-check was the sole guard against hydrating a stale snapshot over a fresher
  commit, reliable only because LRU never evicts the MRU commit mid-window (a guarantee `evictMemory`
  would break). Closed by adding a second guard: the `sequencer` (which advances on every commit under
  `commitGuard`) is captured before the off-lock read, and if it moved by the time the lock is held, the
  authoritative persisted state is re-read under the lock rather than trusting the pre-lock snapshot.
  This is the "re-read under the lock" option, scoped to fire only when the sequencer moved during
  the off-lock read — which is **broader than "a commit raced"**: hydration itself allocates a
  sequence for the entry it installs, so concurrent cold reads of *different* keys interfere: the
  first through the lock hydrates directly, and its sequence allocation pushes every later
  contender onto the guarded re-read, each performed while holding `commitGuard` (for `loadAll`,
  the whole batch is re-read). The *uncontended* path is unchanged, but the cold-read path is **not**
  unchanged under concurrent reads, so a follow-up keys the guard on a commit-only counter (0.5).
  Correctness is not at stake either way — the re-read is authoritative, bounded at one extra read,
  and writers already do their I/O under that lock. Mutation-verified by a deterministic
  one-slot-cache eviction test. *(M)*
- [x] **`invalidateWhere { key -> Boolean }`** — shipped: predicate/bulk invalidation between the
  surgical `invalidate(key)` and the nuclear `invalidateAll()`, for "drop everything for this
  tenant/scope" resets. Each matched key is dropped and fenced under `commitGuard` exactly like
  `invalidate`, in one commit. **Reach is two-tier, and the store decides which tier applies.** An
  *enumerable* `SourceOfTruth` — one whose `keysWhere(predicate)` returns non-`null`, which is what
  `invalidateWhere` actually calls; the SPI default derives it by filtering `keys()`, so overriding
  `keys()` alone qualifies (all the SQLDelight adapter does) and so does overriding `keysWhere`
  directly, for a backend that can select more cheaply than listing — makes the predicate
  **disk-wide**: the union of the keys this process tracks and every persisted
  match, including keys it has never touched. A store that returns `null` (the SPI default) keeps
  the reach in-process-only, and there a persisted-only key stays out of reach (use
  `invalidateAll`). The JSON file store opts out **by design**: its filenames are one-way SHA-256
  of the key, so enumeration would require a separate key→hash manifest with its own
  crash-consistency story. In both tiers the predicate runs *outside* `commitGuard`, so user code
  can never stall or re-enter the store while the lock is held. *(S)*
- [x] **`putAll(entries)`** — shipped: bulk local write, the write-side mirror of `getAll` — seed
  many keys from a manually-fetched batch in one fenced commit, one broadcast per key, each key
  fenced exactly like `put`. *(S)*
- [x] **`snapshot()` / cached-key introspection** — shipped as `snapshot(): Set<K>`, a
  non-suspending peek at the keys resident in memory (`.size` is the live count), for debug
  overlays and eviction tuning; never suspends and never triggers I/O, and is safe to call on a
  closed store. Lists memory only (persisted-but-evicted keys excluded) and returns a stable
  copy. `MemoryCache` now guards its LRU map with a plain monitor (its critical sections never
  suspend) so the read needn't suspend. *(S)*

## 1.0 — the stability contract

- [ ] **API freeze review** — a deliberate pass over every public signature against the locked BCV
  dumps; rename/remove debts now or never. The docket, concretely: *(M)*
  - **`maxAge` symmetry, before the value-class lock.** `maxAge: Duration?` is on `stream` and
    `get` but not on `streamMany`, `getAll`, `prefetch` or `prefetchAll`. Adding a defaulted
    parameter is **binary**-incompatible for any type — the descriptor and the synthetic `$default`
    bridge both change — and `Duration` compounds it: as a value class it mangles the JVM name into
    a signature hash (`get-5_5nbZA`, `stream-moChb0s`), so adding `maxAge` renames `getAll` as well
    as re-signing it. Source compatibility splits by role: *call sites* still compile untouched,
    but because these are members of a fully abstract interface, every third-party **implementor**
    has to edit its override — which is the interface-stance bullet below, arriving as a
    consequence rather than a separate decision. Already-compiled consumers break either way.
    Add it across the multi-key entry points, or decide it belongs on none of them; both are free
    now and neither is later.
  - **The `Aquifer` interface's implementation stance.** 19 members, every one abstract, no default
    bodies — and `aquifer-test` exposes `FakeAquifer` as public API, which implements it. So every member
    added after 1.0 breaks every third-party implementor, while two items on this roadmap (tag
    invalidation and `getAllStates`) want new members — the key-scoped policy resolver does not,
    since it lands as builder configuration. Pick one and
    write it down: default bodies on additive members, `@SubclassOptInRequired`, or "not intended
    for implementation outside this library" in the KDoc.
  - **`…All` vs `…Many`.** `getAll`/`putAll`/`prefetchAll`/`invalidateAll` sit beside
    `streamMany`/`collectAsStateMany`. The split currently tracks "one-shot vs reactive", which is
    a real distinction that the names do not teach. Unify or document the rule.
  - **A per-key failure channel for the batch reads.** `getAll` returns the resolved subset, so a
    caller cannot distinguish "the backend omitted this key" from "this key failed", and it remaps
    `StaleWhileRevalidate` to `CacheFirst` because it is one-shot and awaits the refresh — so
    `getAll(ids, StaleWhileRevalidate)` blocks on the network instead of serving stale (both the
    KDoc and the README now say so; the API still cannot express the per-key failure). A
    `getAllStates(keys): Map<K, DataState<V>>` returns both facts.
  - **`fresh` as an extension.** `fresh(key)` is literally `get(key, NetworkOnly)`, so it can move
    out of the interface as a plain extension: implementors stop having to write it and the frozen
    surface shrinks by one. `evictMemory`/`trimToSize` **cannot** follow — an extension has no way
    to reach an arbitrary implementation's memory tier, so they either stay members or move behind a
    separate opt-in memory-management capability interface. Decide which.
  - **The default `timeToLive`.** It is `Duration.INFINITE`, and `isExpired` takes the first
    horizon that applies (`maxAge ?: freshFor ?: timeToLive`) and compares `elapsed >= horizon`.
    So for an entry carrying neither override, a store built without a `freshness { }` block
    never refetches under `CacheFirst`, never revalidates under `StaleWhileRevalidate`, refreshes
    only the active keys with nothing cached on reconnect, and reports `isStale = false` forever.
    Every README example happens to set a TTL, which is why the default has never bitten. Changing
    a default is a behavior break after 1.0 and free before.
- [ ] **Semver policy + CHANGELOG discipline** documented — what "public API" covers (the BCV
  dumps, not the internals), what a pre-1.0 source break costs, and one entry per change so
  the `[Unreleased]` sprawl the 0.1.0 tag cleans up does not simply re-accumulate. The
  release-notes automation half of this item moved to Now. *(S)*
- [ ] **"Coming from a hand-rolled repository" guide** — the second half of the migration set (the
  Store5 guide moved to Now): the `MutableStateFlow` + `suspend fun refresh()` pattern most teams
  already have, and what Aquifer replaces in it — single-flight, epoch fencing, process-death
  survival. *(S)*
- [ ] **Supply-chain hardening** — a `dependency-review-action` gate and a CodeQL workflow on
  PRs (Dependabot bumps versions but does not CVE-alert the existing tree), GitHub Actions
  pinned to commit SHAs, and build-provenance/SLSA attestation on the release artifacts (the
  release job currently has no top-level `permissions` block and signs only with the Maven PGP
  signature). Cheap, standard insurance for a widely-embeddable library. *(S)*

## Beyond 1.0 — strategic bets

**Targeting statement for the 0.x line:** Aquifer targets Android apps and JVM services. A team
that needs to share one data layer with iOS should use Store5 today — that is a real answer, not a
deflection, and it stays the answer until the bet below lands.

- [ ] **Kotlin Multiplatform core** — dispatcher-clean coroutines are the easy half; the
  engine's JVM concurrency also has to move: the `ConcurrentHashMap` CAS loops (`inFlight`,
  `activeKeys`, `keyEpochs`), `AtomicLong`/`AtomicBoolean`, and the `LinkedHashMap`-based
  LRU need KMP equivalents (atomicfu, mutex-guarded maps), plus a `kotlinx-io`/okio port of
  the file store, iOS/desktop targets, an iOS sample, and lifting `aquifer-compose` to Compose
  Multiplatform. The load-bearing portability risk is the file store's durability guarantee:
  it rests on `FileChannel.force(true)` + `ATOMIC_MOVE`; okio's `FileSystem.atomicMove` provides
  the rename but a portable `fsync` is not yet a given, and the SHA-256 filename needs a
  multiplatform hash (okio `HashingSink` or kotlin-crypto) — scope the backend before committing.
  The largest differentiator on the list, and correctly the last thing on it: it is XL work whose
  value is entirely hypothetical until the JVM/Android line has users. *(XL)*
  - [ ] **Ktor client fetcher helper** (`aquifer-ktor`) — a sub-step of this bet, not a
    free-standing item: a `ktorConditionalFetcher`/`ktorFetcher` mirroring the OkHttp helper
    (ETag/Last-Modified ↔ `If-None-Match`/`If-Modified-Since`, 304 → `NotModified`, sharing the
    typed-status-error contract). OkHttp is JVM-only, so this is what serves non-JVM targets;
    build it alongside the `kotlinx-io`/okio file-store port so a real target exercises it. *(M)*
- [ ] **Offline mutations** (`aquifer-mutations`) — the write-side counterpart to Aquifer's
  read-side: an optimistic-update queue with rollback and conflict hooks, surviving process
  death via the same `SourceOfTruth` machinery. This is the single biggest capability gap vs
  both incumbents (Store5's `MutableStore`/`Updater`/`Bookkeeper`; TanStack/RTK `useMutation`
  with optimistic update + rollback). Note what `put()` is and is not today: it is an
  **authoritative local write**, fenced against in-flight fetches, that stands until it goes stale
  under the staleness-aware strategies — the *effective* horizon, which a per-call `maxAge` or
  `ttlJitter` can bring forward — but which does not hold at all under
  `NetworkFirst`/`NetworkOnly`/`fresh`, which fetch regardless — and is then silently replaced by
  the first successful, unfenced fetch: no rollback, no conflict hook, no
  event distinguishing "your write" from "the server's answer". That is *not* what "optimistic"
  means in Store5/TanStack/RTK, where it means provisional-pending-confirmation with rollback, and
  the gap is a data-loss trap for anyone wiring an offline edit form. Consider pulling a **minimal
  optimistic-`put`-with-rollback slice** forward as its own smaller item to de-risk the top
  differentiator before the full module — it leans on the registration-fencing fix (#42), since a
  racing in-flight fetch must not clobber an optimistic local write. *(XL)*
- [ ] **Paging bridge** (`aquifer-paging`) — keyed page caching behind AndroidX Paging 3.
  *(L)*

## Non-goals

Declared so the scope stays honest: image/blob caching (use Coil), cross-process shared
caches, full sync engines/CRDTs, GraphQL response normalization, reflection-based
serialization, and Java-first API surface.

---

Suggestions welcome — open an issue. Open items are ordered by expected leverage and get re-sorted
as reality disagrees; shipped items stay put, in ship order, as the record of how the library got
here.
