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
all landed. What remains is the changelog re-fold below, then owner action — the four signing secrets
and the version bump off `-SNAPSHOT`.

- [ ] **Publish v0.1.0 to Maven Central** — add the four secrets from
  [CONTRIBUTING](CONTRIBUTING.md), bump the (newly single) `version` in `gradle.properties` off
  `-SNAPSHOT`, confirm the `0.1.0` CHANGELOG date still matches the tag date, fast-forward `main` to
  `develop`, push `v0.1.0`. The workflow verifies and *stages* the deployment; publication is a
  deliberate click in the Central Portal, after which the **Cut a GitHub Release** workflow
  announces it. Full walkthrough in [CONTRIBUTING](CONTRIBUTING.md). *(owner action — S)*
- [ ] **Re-fold `[Unreleased]` into `0.1.0` before tagging** — the collapse below shipped, and
  since then `[Unreleased]` has re-accumulated above the dated section: the `revalidateActive(force)`
  parameter (a signature change on the interface), the batched and `maxAge`-aware reconnect sweep,
  the commit-only hydration guard, the widened sample, the Store5 guide and the staged-release
  workflow. `changelog-section.sh` extracts the `## [0.1.0]` section *only*, so tagging now would
  publish artifacts containing all of that and announce notes that mention none of it — and
  CONTRIBUTING's step 1, "add a dated section", reads as already done, which is exactly how the trap
  gets sprung. Fold them in, re-date the heading, and leave `[Unreleased]` empty at the tag; the
  version gate cannot catch this, because the section exists. *(S)*
- [ ] **Maven Central badge + install snippet verification** after the first release — resolve the
  published coordinates from a clean project, and confirm the snippet still lists all seven
  published modules. *(S)*
- [ ] **Publish an `aquifer-bom`** — seven artifacts move in lockstep and, per the CHANGELOG header,
  pre-1.0 minors may break binary compatibility, so `aquifer-core` 0.2.0 next to `aquifer-compose`
  0.1.0 fails at link time rather than at compile time. A Maven BOM — a `java-platform` module
  published through the same plugin, so it joins the `publishingModules` gate automatically — makes
  lockstep the default and turns the install snippet into one version line. Cheapest right after
  the first release, while the coordinates are still being written down. *(S)*
- [x] **"Coming from Store5" migration guide** (shipped) — `docs/coming-from-store5.md`, linked from
  the README's comparison section. Written against Store5's current documentation rather than from
  memory, which turned up two things the planned mapping had wrong. First, this file's own shorthand
  "epoch fencing instead of bookkeeping" conflated unrelated mechanisms: `Bookkeeper` tracks failed
  local mutations for the write path, epoch fencing stops an in-flight fetch resurrecting deleted
  data — the guide says so instead of repeating the equivalence. Second, and the reason the guide
  leads with deal-breakers rather than a mapping table: Store5's `SourceOfTruth.reader` returns a
  `Flow`, so a write its backing database observes propagates, whereas Aquifer's `read` is a plain
  suspend function — an active stream is **never notified** of a write Aquifer did not make, though a
  later cold read still picks it up. A Store5 user relying on that gets silence, not an error. Also covers the `Converter` triple-type surface having
  no counterpart, and the infinite-TTL default versus Store5's `Validator`. *(S)*
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

- [ ] **`peek(key)` and a flash-free first frame in Compose** — `collectAsState` starts at
  `Loading(null)` and holds it until the stream's first emission, which needs a dispatch and a
  `load()` even when the entry is sitting in memory. So every navigation *back* to a screen renders
  at least one frame of skeleton over data the store already has — the flash the library exists to
  prevent, reintroduced at the last step. The engine already has the primitive: `memory.get` is a
  non-suspending monitor read, the same class of operation `snapshot()`/`stats()` expose. A
  `peek(key, maxAge: Duration? = null): DataState.Content<V>?` — memory only, no I/O, no fencing,
  safe on a closed store — lets `collectAsState`/`collectAsStateMany` seed their `initialValue`
  from it. It takes the same `maxAge` as `stream` and judges `isStale` by the same precedence
  (per-call `maxAge`, else the entry's server horizon, else the jittered store TTL), so the seeded
  frame and the stream's first emission agree — `collectAsState` forwards the `maxAge` it already
  holds. The bus subscription that follows still catches anything newer, exactly as `prime()` does
  today. Seeding is for the cache-reading strategies only: `Freshness.NetworkOnly` bypasses memory
  and persistence by contract, so under it the initial state stays `Loading(null)` (the empty map
  for the multi-key form), exactly as the stream itself skips its preload. It is a new interface
  member, so it lands on the implementation-stance decision in the 1.0 docket; `previewAquifer` and
  `fakeAquifer` get trivial bodies. *(S–M)*
- [ ] **Preview states, not just values** — `previewAquifer` seeds *values*: a stream emits
  `Content(value, MEMORY)` or `Empty`, nothing else. The two layouts a `@Preview` most needs to show
  — the loading skeleton and the failure banner — cannot be previewed at all, and neither can the
  stale badge (`isStale` is always `false`). Let the seed carry a `DataState` per key
  (`previewAquifer { content("u1", ada); loading("u2", cached = grace); failure("u3", error) }`, or a
  `Map<K, DataState<V>>` overload), with `get`/`getAll` deriving from it the way the real store's
  stale fallback does: a state that carries a value returns it (`Loading(cached)` and
  `Failure(error, cached)` included), a valueless `Loading` or `Empty` is a miss, and a valueless
  `Failure` throws its error. Pure addition; the existing `vararg Pair<K, V>` entry point keeps its
  meaning. *(S)*
- [ ] **A recipes page** — the README explains each knob once; the questions that arrive after a
  release are combinations: a singleton (`Aquifer<Unit, Config>`); "404 is a value" (`V : Any`, so
  absence has to be modelled *in* `V` — to the store a 404 is a *failure*, which the negative cache
  remembers as one and every fetch-capable stream renders as `Failure`, never as `Empty`);
  search/autocomplete (key = query, `coalesceWindow`, `negativeCache { maxEntries }`, and why
  `keyEpochs` grows — #13); tenant scoping and logout
  (`invalidateWhere` reaches disk only on an enumerable store, so the file store needs
  `invalidateAll`); data-class keys and `keyEncoder` stability across refactors; blocking fetchers
  and `Dispatchers.IO`. One `docs/recipes.md`, each recipe a compilable snippet, linked from the
  README's core-concepts section. *(S)*
- [x] **Widen the CLI sample past its first five scenarios** — the original five (cold start, SWR,
  `put`, "process death", reconnect-with-retry) are now scenarios 1-5 of a `coreLoopTour`, followed
  by a `featureTour` covering single-flight dedup, `prefetch`, batched `getAll`, conditional (304)
  fetching, negative caching, and the `stats`/`snapshot` counters — each with its own store and
  purpose-built fake backend, so a scenario's assertions (`1 API call for 5 concurrent reads`,
  `1 call for 3 reads of a dead endpoint`) come from real counters rather than narration. Since CI
  runs `:sample:run` once per workflow (gated on the JDK-21 matrix leg), each is also a free
  end-to-end smoke test of a headline path. Batching surfaces one wrinkle worth the log line it
  got: events stay per-key, so five `fetch started`s precede the single batch call.
  `streamMany`/`prefetchAll` are left out as reactive/warm-up variants of paths already shown, and
  encryption and migration stay out because both are `SourceOfTruth` configuration rather than a
  scenario. *(S)*
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

- [ ] **A second horizon: how long stale is still servable** *(design first; decide before 1.0)* —
  the store has exactly one horizon, freshness, and past it a value is servable forever:
  `StaleWhileRevalidate` serves week-old data with `isStale = true`, and stale-if-error falls back
  to it without limit. Real policies have a ceiling — "show cached prices, but never ones older than
  a day" — and expressing it today means the caller inspecting a `writtenAtMillis` it cannot see.
  Add `freshness { maxStale }` (past it the entry is treated as *missing*: `Loading(null)`, a
  `CacheMissException`, a fetch that fails without fallback) and let the origin declare it too:
  RFC 5861's `stale-while-revalidate=N` and `stale-if-error=N` are precisely Aquifer's two stale
  behaviours, named by the standard the library is named after, and `okHttpConditionalFetcher`
  currently ignores both (`respectCacheControl` would parse them). They are **independent**
  horizons — an origin can send `stale-while-revalidate=30, stale-if-error=86400` — so the entry
  carries two (`staleWhileRevalidateFor`, `staleIfErrorFor`), not one collapsed `staleFor`, and
  each stale behaviour consults its own. The builder's `maxStale` is the app's ceiling on both:
  a ceiling composes by `min` with whatever the server declared, not by the precedence the
  freshness horizon uses, since "serve stale no longer than this" is a promise the app makes
  regardless of the origin's generosity. State its reach precisely, because it is narrower than the
  wording suggests: like `isStale` today, the ceiling is judged when the store reads or emits — a
  `get`, a stream's prime, an event on the bus — and nothing schedules a wake-up at the boundary,
  so a collector that has gone quiet keeps its last `Content` past `maxStale` until something is
  emitted. The promise is "never *served* past this", not "never *displayed* past this"; a timer at
  the horizon is a separate decision, and one that would apply to `isStale` just as much. The catch
  is storage shape: two fields next to `freshFor`
  on `FetchResult.Fresh` and `PersistedEntry`, both locked `data class`es whose constructors,
  `copy` and `componentN` all change — binary-breaking after 1.0, defaulted and free before it (the
  on-disk envelope takes them the way it took `serverFreshForMillis`). Ship the engine seam and the
  parser separately, as #50/#51 did. *(M)*
- [ ] **Honour `Retry-After`** — `HttpException(code, url)` keeps the status and drops the headers,
  and `retry { }` backs off on a fixed exponential schedule, so a `429` or `503` carrying
  `Retry-After: 30` is retried after at most 250 ms, then 500 ms, straight into the same wall — the
  one signal a well-behaved client is required to obey, discarded at the seam that was built to
  carry status. Parse it in both OkHttp helpers (delta-seconds and HTTP-date) onto
  `HttpException.retryAfter: Duration?` — a defaulted secondary constructor, since the two-argument
  one is locked. The wiring has to cross the module boundary by itself, because `aquifer-core`
  cannot see `HttpException`: add a one-property core interface, `RetryAfterHint { val retryAfter:
  Duration? }`, that `HttpException` implements, and have the retry loop consult it on every
  failure it is about to back off from — the hint replaces the computed delay outright
  (`maxDelay` does not cap it; the server's instruction is the point), `retryOn` still decides
  *whether* to retry, and `onFetchRetried` reports the delay actually used. `RetryConfig` also gains
  `delayFor: (Throwable, attempt: Int) -> Duration?` as the manual override for a transport that
  carries the header some other way, `null` meaning "use the schedule". Whether it also seeds the
  negative-cache window is a second decision: a server-declared
  30 s suppression is exactly what that window is for, but the streak arithmetic should not
  multiply it. *(S)*
- [ ] **One `NetworkCallback` per process, and a validated network** — `revalidateOnReconnect`
  registers a `ConnectivityManager.NetworkCallback` per call for the store's lifetime, so an app
  with a store per data family holds N registrations, each an IPC target on every network event,
  and Android 11+ caps a process at 100 and throws `TooManyRequestsException` past it — a limit an
  app reaches by following this library's own "one Aquifer per data family" advice. Share one
  ref-counted `connectivityRestoredFlow` per `applicationContext` (a `shareIn`-style upstream that
  registers on the first collector and unregisters after the last). Separately, the request asks
  only for `NET_CAPABILITY_INTERNET`, which a captive-portal Wi-Fi satisfies before any byte can
  pass: the reconnect sweep fires into the portal, fails, and — with negative caching on — the
  failure memory then suppresses the sweep for the *real* reconnect that follows. Requiring
  `NET_CAPABILITY_VALIDATED` is the standard fix and is API 23+, the same seam the Robolectric
  multi-SDK item in 0.5 exists to test; below 23 keep today's behaviour and say so. *(S)*
- [ ] **Chunk explicit batches by `maxBatchSize`** — the cap is honoured by the coalescing
  accumulator alone. `getAll`, `streamMany`, `prefetchAll` and the reconnect sweep hand `startBatch`
  their whole key set and it dispatches one call, so a backend that accepts at most 100 ids per
  request — the usual shape, whether from URL length or an explicit cap — receives 500 and answers
  with an error that fails every key. Callers currently chunk by hand and lose the single
  round-trip they configured a batch fetcher for; the SQLDelight adapter already does the same
  chunking at the storage layer for the same reason. Split the started set into `maxBatchSize`
  chunks, each its own retry-all unit (a failing chunk fails only its keys; `onFetchRetried` stays
  per key), and let the cap be set without a coalescing window — today it lives only on the
  windowed `batchFetcher` overload, so a non-coalescing store cannot express it. *(S)*
- [ ] **Bound what a stalled collector can buffer** *(design first)* — every stream drains the bus
  through a `Channel.UNLIMITED` buffer so that one slow screen can never stall the engine; the
  README sells the isolation and the KDoc names the cost, which is unbounded memory per stalled
  collector on a busy store. `DataState` is a snapshot, not a log: a collector only ever needs the
  newest state of its key, and the watermark logic already rejects anything older than what it has
  applied. So the buffer can be *conflated* — keep the latest `Updated`/drop and the latest
  transition for the collector's key — instead of replaying history. The trade to write down,
  because it is a behaviour change and not something the stream already does: a collector that
  stalls through a `Fetching` → `Updated` pair sees only the `Updated`. Today it sees both —
  `distinctUntilChanged` compares `Loading` and `Content`, which are different types and never
  equal — so conflation deliberately drops an observable loading state on a stalled collector.
  That is the right call for a UI that was not rendering while it stalled and lands on the final
  state regardless, but it is the contract to state, not an existing behaviour to point at. What
  is **not** an option is a plain capacity cap with drop-oldest: each collector buffers the
  *store-wide* bus and filters by key afterwards, so under a cap unrelated keys' traffic can push
  out the one event the collector needed and leave it stale for good. Keyed conflation, or
  drop-with-resync (an overflow marks the tracker dirty, and on resume it re-reads memory for its
  key under the same watermark rule a new subscriber uses), are the only sound shapes.
  `BackpressureTest` today proves the *writers'* side — a stalled collector blocks neither `put`
  nor other callers — and says nothing about what the stalled collector eventually sees; the
  change needs the missing half: release the collector and assert it lands on the final
  state. *(M)*
- [ ] **Stop the refresh path re-reading each entry for its validator** *(deprioritised — see the
  hazard below)* — on any validator-aware store (`conditionalFetcher` *or*
  `conditionalBatchFetcher`), every `refreshWith` slice calls `load(key)` to obtain `prior` before
  invoking the transport, and feeds it to `resolve()` as what a `NotModified` resolves against. A
  caller that has *already* loaded and fenced the entry — `revalidateActive`'s sweep does exactly
  this — pays for it twice, and on an active set wider than `memoryCache.maxEntries` the second read
  hits persistence per key because the first only warmed memory.

  **The obvious fix is unsafe, and the reason is worth writing down before someone tries it.**
  Handing the caller's snapshot straight to `refreshWith` loses a property the current placement
  gives for free. The read sits *after* registration, inside the lazily-started body; a snapshot
  taken by the caller is necessarily from *before* it. In that gap an already-in-flight fetch can
  commit — a fetch commit does **not** move the epoch — and then `beginOrJoin` finds nothing in
  flight and starts a new one. Sequence: F1 in flight for K → the sweep's `loadAll` captures E0 →
  F1 commits E1 → the sweep registers and wins → the transport answers `NotModified` → `resolve()`
  commits **E0 over E1**, unfenced, because the epoch never moved. Today's per-key `load(key)`
  cannot see that stale state: it runs after registration, so it observes E1.

  A safe version therefore has to *validate* the snapshot rather than trust it — carry the
  `commitGen` reading from when it was taken and fall back to `load(key)` if it has moved, which is
  the same guard shape used for residual hydration. That is buildable, but it turns a "pass the
  value you already have" change into another piece of fencing-sensitive machinery.

  **Which is why this is deprioritised rather than open-and-ready.** The saving is N memory lookups
  in the common case (entries resident, negligible) and N persistence reads only when the active set
  exceeds `memoryCache.maxEntries` — a real case, but a narrow one, since the default cap is 512 and
  an active set is what a user has on screen. That does not obviously justify another guarded path
  through the fetch commit. Worth revisiting if a profile on a small-`maxEntries` store says
  otherwise. *(S–M)*
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
- [x] **Decide what a local `put` does to the validator** (decided — keep dropping) — `put`/`putAll`
  write `PersistedEntry(value, now)`, dropping the entry's `validator` and `serverFreshForMillis`,
  so a locally written key loses its ETag and its next conditional fetch goes out unconditional: a
  full-body download where a 304 looked available, on precisely the keys an app edits most. The
  alternative on the table was retaining the validator behind a "locally modified" marker that
  suppressed the re-age.

  **The bandwidth saving is real; the store cannot safely take it.** Be precise about the trade,
  because "a 304 saves nothing" would be false: retaining the token genuinely does avoid a body
  when the server is unchanged. A validator identifies the *server's* representation, though, and
  after a local `put` the cached body is not that representation. On `304` the server is saying
  *"I still hold the version you overwrote"* — no body arrives, and `resolve()` commits
  `NotModified` as `prior.value`, so the store would publish the local edit re-aged as
  server-confirmed. Taking the saving safely needs local-modification and conflict state Aquifer
  does not keep.

  It is also the wrong saving to want. A refresh of a locally written key exists to *replace* that
  write with the server's version — which is what the unconditional `200` delivers, and which the
  `304` withholds by design. The bytes it spares are the bytes being asked for.

  Making it useful would mean keeping the pre-edit server body alongside the local one so a 304
  could raise a conflict — which is an outbox with conflict handling, i.e. the **Offline mutations**
  (`aquifer-mutations`) item in 0.6, not a knob on `put`. This is therefore a consequence of `put` not being
  a mutation queue rather than a missing optimization, and the KDoc, README and this entry now say
  so instead of calling the drop merely "defensible". Behaviour unchanged; pinned by
  `a local put clears the validator`. *(S)*
- [x] **Fix `revalidateActive()` — batch it, honor per-stream `maxAge`, add a force knob** — the
  reconnect path walked `activeKeys` doing a per-key `load()` then `refresh()`. All four parts have
  shipped: batched reads, per-stream `maxAge`, the force knob, and batched fetches.

  **The read side is shipped.** `load()` returns from memory first, so the storage hit was per
  *non-resident* active key rather than per key — but on the cold reconnect that matters (process
  resumed, memory shed) that is precisely when every key is a miss, so it was N sequential reads on
  the path most likely to be cold. The sweep now snapshots the active set and resolves it through
  the existing `loadAll`, which issues a batched `SourceOfTruth.readAll` with the same epoch fencing
  and residual-hydration guard `load` applies — so a store overriding `readAll` serves the whole
  sweep as one bulk lookup, and one leaving it at the per-key default is no worse off than before.
  "Bulk", not "a single query": `loadAll` re-reads under the commit lock when a write races the
  first read, and the SQLDelight adapter chunks at SQLite's host-parameter cap, so a wide active set
  is still several statements.

  **Per-stream freshness is shipped.** `activeKeys` was a bare `ConcurrentHashMap<K, Int>` refcount,
  so a `stream(key, maxAge = 30.seconds)` was revalidated against the store-wide TTL instead of the
  bar its caller asked for. It now maps each key to the multiset of `maxAge` bars its collectors
  declared, and the sweep refreshes when *any* of them considers the entry stale — the tightest bar
  wins, and they share the one resulting fetch regardless. That also draws the sting from the
  **default** `timeToLive = Duration.INFINITE`, under which an entry carrying no server-declared
  `freshFor` never expires: such a store used to revalidate *only the active keys with nothing
  cached, plus any whose server horizon had elapsed* while looking like it refreshed everything on
  screen, and a stream that declares a `maxAge` is now swept on that bar once it elapses. A multiset
  rather than a single tightest bar, because unregistration has to drop exactly the bar its own
  stream added.

  **The fetch side is shipped.** The sweep's per-key `refresh()` calls used to be merged only by
  the accumulator, which exists solely when a plain `batchFetcher` is paired with a positive
  `coalesceWindow`; `conditionalBatchFetcher` and a window-less `batchFetcher` stayed N calls. The
  sweep now collects the keys it decided to refresh and hands them to `startBatch` — `getAll`'s
  transport — so one call covers the screen. Routing rather than reinventing is what made it small:
  the per-key slices are `refreshWith` registrations, so single-flight, epoch fencing and per-key
  events are untouched by construction, and a key already in flight joins that fetch instead of
  being re-requested. Skipped keys (fresh, `CacheOnly`-only, negative-cached) are filtered before
  the call, so suppression still holds. Two honest limits: a store with only a single-key
  `fetcher`/`conditionalFetcher` has no multi-key transport, so it still issues one fetch per stale
  key and always will; and a coalescing store now dispatches immediately instead of feeding the
  accumulator, matching `getAll`.

  It did **not** take the sweep's *validator* reads with it, which the earlier plan assumed it
  would. `runBatch` does gather validators through a single `loadAll`, but that is on top of, not
  instead of, the per-key read: `refreshWith` computes `prior` via `load(key)` before invoking any
  transport and then feeds it to `resolve()`, which is what a `NotModified` resolves against — so
  the read is load-bearing, not incidental, and every slice still makes it. On a wide active set
  (more keys than `memoryCache.maxEntries`) those degrade to per-key persistence reads exactly as
  before, since the sweep's own batched load only warms memory and the warming is best-effort.

  So batching the *fetches* did nothing for the validator reads on either conditional path. Closing
  that means handing the sweep's already-loaded, already-fenced snapshots to the refresh path so it
  stops re-reading — which moves a read that sits inside the fetch body on purpose ("the entry as it
  stood when the fetch started"), and therefore wants its own change and its own Lincheck run rather
  than riding along with a routing patch. **Left open below.**

  **The force knob is shipped**, as `revalidateActive(force = false)` — a defaulted parameter
  rather than a second method, so the two behaviours stay visibly one operation and Kotlin call
  sites written `revalidateActive()` recompile untouched. Only those: the interface method's JVM
  descriptor gains the boolean, so pre-compiled code fails to link, Java call sites must pass the
  argument, and direct `Aquifer` implementors must update their override — breaking rather than
  additive, as the changelog entry spells out. Pull-to-refresh had no way to express itself before: every route
  into the sweep judged staleness first, which is exactly what a user yanking the list down is
  overriding. The deliberate limit is that `force` overrides *staleness only* — a key inside a
  negative-cache suppression window is still skipped, since that window remembers a failing
  endpoint rather than a fresh value and a sweep touches every key on screen at once, so bypassing
  it would turn one gesture into a burst against a backend already known to be down. `fresh(key)`
  stays the per-key override that ignores the failure memory as well. A forced sweep on a
  *non-conditional* store also reads no storage, since loading first only ever served the judgement
  it is skipping — non-conditional meaning neither `conditionalFetcher` nor
  `conditionalBatchFetcher`, both of which mark the store validator-aware and so still need the
  entries loaded. *(M, shipped)*

## 0.4 — Persistence expansion

Meet apps where their storage already is. The two SPI capabilities came first: both adapters'
whole point (native batched transactions, a disk-wide `invalidateWhere`) is inexpressible
through a single-key `SourceOfTruth`, so building the adapters first would either hardcode
N-round-trip behavior or force a contract break mid-milestone.

- [ ] **Adapter parity — or a written reason for the gap** — the file store has
  `maxEntries`/`maxBytes`, `cipher`, and `schemaVersion`/`migrate`; the SQLDelight store has none of
  the three, so the README's bounded-disk, encryption-at-rest and migration sections silently
  apply to one adapter. Each gap has a different right answer. *Bounding* is easier in SQL — an
  access-time column and `DELETE FROM entry WHERE key IN (SELECT key FROM entry ORDER BY
  accessedAt LIMIT …)`, the subquery form rather than `DELETE … ORDER BY … LIMIT`, which only
  compiles on a SQLite built with `SQLITE_ENABLE_UPDATE_DELETE_LIMIT` and a consumer-supplied
  driver does not guarantee that — but the table has no such column and `readAll` records no
  recency, so it is a schema change, which is the next item's concern. *Value migration* is
  cheap parity: the value is JSON text, so the same envelope trick applies.
  *Encryption* is a genuine decision: SQLCipher encrypts the whole database, keys included, which a
  `ValueCipher` on the value column would leave in plaintext — so parity may be the wrong answer
  and "use a SQLCipher driver" the right one, but then the KDoc and README must say so wherever
  they advertise the `cipher` seam. Do the two, decide the third, and document the matrix. *(M)*
- [ ] **Own the SQLDelight table's evolution** — `Entry.sq` is at schema version 1 with no
  migrations, and the KDoc hands the schema lifecycle to the caller: "the store itself never creates
  or migrates the schema". That is the wrong owner for a table the caller did not design. The day
  Aquifer adds a column — the file envelope has already grown `validator`, `serverFreshForMillis`
  and `schemaVersion`, each defaulted — every existing database is at version 1, `Schema.version`
  reads 2, and the consumer is asked to write a migration for someone else's table. Commit to
  shipping a `.sqm` with every schema change, turn on `verifyMigrations` (and derive the schema
  from the migrations) so CI proves the chain from every past version, and document
  `Schema.migrate(driver, oldVersion, newVersion)` as the consumer's one call. While here, run this
  module on the JDK 11 launcher too: it is the only publishing module the `jvm11-runtime` job
  skips, and #46 recorded no reason — establish whether that is a real incompatibility of the test
  driver (then say so, and that the *module* is still JVM-11 bytecode) or an oversight. *(S–M)*
- [ ] **Expired-entry purge** *(design first)* — flagged as "a separate companion" when
  `evictMemory` shipped and never listed since. Staleness is judged on read; nothing ever *removes*
  an expired entry. In memory the LRU bounds that, so the cost is resident dead entries until
  pressure. On disk nothing does: an unbounded file store keeps every key ever fetched, and a key
  space of search queries or paginated ids grows a directory forever — the file store's
  `maxEntries`/`maxBytes` are the mitigation, not a fix, and the SQLDelight store has neither. The
  first design decision is what "expired" means here, because it cannot mean *stale*: a stale
  entry is still a valid `StaleWhileRevalidate` and stale-if-error fallback, staleness varies per
  entry (a server `freshFor`) and per caller (a `maxAge`), and the second-horizon item in 0.3 adds
  more per-entry horizons. Purging on any of them deletes data another strategy or caller could
  still serve. So define purge against an explicit **retention** — `freshness { retention }`, an
  absolute age from `writtenAtMillis` that no horizon can extend (the app promises "nothing older
  than this is kept", and sets it at or above every stale ceiling it wants honoured). On that
  definition the memory half is trivial: a sweep under the memory monitor, silent and unfenced like
  `trimToSize`. The disk half is the mechanism: the cutoff is a single timestamp, so the SPI gains
  a `deleteWrittenBefore(millis)` — sound precisely because retention is defined on the write time
  alone — that a SQL store answers in one statement, and that the file store answers too, since it
  is the store this item is *about*: hashed filenames prevent enumerating *keys*, and a purge
  deletes by *file*. It walks its directory and drops every envelope whose `writtenAtMillis` is
  below the cutoff, keeping the LRU accounting in step — a full scan, and under a `cipher` a
  decrypt per file, because the timestamp lives inside the envelope — so it is an explicit call,
  not something a write triggers. File mtime is the cheap approximation the LRU index already
  leans on, but it is the filesystem's clock rather than the store's `WallClock`, so exactness
  means reading the envelope. A store that leaves the default (`null`, unsupported, like `keys()`)
  falls back to engine enumeration — `keysWhere` + `readAll` + `deleteMany` — which reaches only
  enumerable stores. Either way it is an `Aquifer` member, so it queues on the interface-stance
  decision in the 1.0 docket. *(S–M)*
- [ ] **A Windows leg for the file store** — "JVM services" is a stated target and every CI job is
  `ubuntu-latest`. `moveIntoPlace` falls back to a plain replace only on
  `AtomicMoveNotSupportedException`; on Windows an atomic replace of a file that a concurrent
  `readBytes` still holds open is expected to fail with a different `FileSystemException`, which
  would surface as a thrown `put` or a failed write-through rather than the documented "reads see
  the old entry or the new one". Expected, not verified — which is what the leg is for. One
  `windows-latest` job running `:aquifer-persistence-file:test` (and `:aquifer-core:test`) answers
  it cheaply; if the hazard is real, the fix is a bounded retry on the sharing violation or a
  documented Windows caveat, and either beats the current silence. *(S)*
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

- [ ] **Make fencing observable, and stop reporting a discarded fetch as a success** —
  `onFetchSucceeded` fires *before* `resolve()` and `commitFetched()`, so a fetch whose commit the
  epoch gate drops — the headline guarantee doing its job — is reported as a success and then
  thrown away with no event at all. An app cannot count how often fencing saves it, and a metrics
  bridge over `AquiferEvents` over-reports. Nor can it tell a `304` from a full body: the bandwidth
  `aquifer-okhttp` exists to save is unmeasurable short of wrapping the fetcher. The seam is
  additive as long as it stays *new members only*. The locked dump compiles the interface's
  default bodies as JVM default methods (`public fun`, not `public abstract fun`, with
  `DefaultImpls` kept alongside for compatibility), so a listener compiled against today's
  interface inherits a new callback's default body — whereas a parameter added to an existing
  callback is a new descriptor (and, with a `Duration` in it, a new mangled name) that breaks every
  compiled and every source override. So `onFetchSucceeded` stays exactly as it is, and the
  additions are new callbacks: `onFetchDiscarded(key)` for the fenced commit,
  `onFetchNotModified(key, duration)` for a 304 (fired alongside `onFetchSucceeded`, whose contract
  does not change), `onEvicted(key)` for LRU drops — plus matching `CacheStats` counters
  (`fetches`, `fetchFailures`, `notModified`, `discarded`) so `stats()` can answer "what is my 304
  ratio" without a listener — `CacheStats` being a `data class`, that half is pre-1.0-only (see the
  docket). Also missing, at the adapter level: the file store maps an `IOException` on read to
  "absent, file kept", so a permissions or mount problem looks like a permanently cold cache with no
  signal anywhere. Closing that needs the store to be *allowed* to say so — let `read` throw
  transient I/O errors, with the engine catching them, treating the key as a miss and reporting an
  `onPersistenceReadFailed` that mirrors the write-side hook — rather than an SPI that flattens
  every failure into `null`. *(S–M)*
- [ ] **Enforce the API-21 promise mechanically** — the README promises `aquifer-core` is
  "deliberately free of `java.util` methods added in API 24", and `ActiveKeyRegistry` hand-rolls its
  CAS loops to avoid `ConcurrentHashMap.merge`/`compute` for exactly that reason — but nothing
  checks it. `aquifer-core` is a plain JVM module, so Android Lint's `NewApi` never sees its
  sources, and JDK 11 compiles `compute` without comment; the promise holds only as long as every
  reviewer remembers it. Two candidate guards: `animal-sniffer` against the `android-api-level-21`
  signature jar on the JVM modules, or Lint from `aquifer-android` with `checkDependencies = true`.
  Pick whichever demonstrably fails on a deliberate `compute` call, and keep that call as the
  negative test. *(S)*
- [ ] **A persistence test kit** — `aquifer-core` keeps an `InMemorySourceOfTruth` in its *test*
  sources, so a consumer wanting to test the real engine against persistence without touching disk
  writes their own, and the author of a custom `SourceOfTruth` has nothing to run their store
  against: the SPI contract is six paragraphs of prose (null for undecodable, `readAll` omission,
  `keys()` empty versus `null`, non-atomic `writeAll`, safety under concurrent calls) and no check.
  Publish the in-memory store from `aquifer-test`, with failure and latency injection, and an
  abstract contract suite (`AbstractSourceOfTruthContractTest`) that the two shipped adapters run
  in their own test sets and a custom store's author subclasses. It is also the right first step
  for the adapter-parity item in 0.4: a shared suite is how parity gets verified rather than
  asserted. *(M)*
- [ ] **Adopt AGP 9 and unpin the Gradle wrapper** — Dependabot is told to ignore wrapper versions
  from 9.6 because Gradle 9.6 removed an internal API AGP 8.x still uses. A standing ignore rule
  ages silently: the wrapper stops moving, nothing reports it, and the Kotlin and Compose plugin
  bumps that keep arriving eventually assume a Gradle the wrapper cannot reach. Bump AGP, drop the
  rule, and let the wrapper catch up in the same PR. *(S–M)*
- [ ] **Robolectric multi-SDK config** — `Connectivity.isCurrentlyOnline()` uses the deprecated
  `allNetworks` because its replacement needs API 23 while `minSdk` is 21, and both Android test
  classes pin `@Config(sdk = [35])`. So the compatibility branch is exercised *only* at the API
  level where it is deprecated and never at the ones it exists for, and `minSdk = 21` is a promise
  the suite does not keep. Run the connectivity tests across a low/high SDK pair. *(S)*
- [ ] **Point Lincheck at the concurrency that is actually hand-rolled** — two of the shipped
  classes prove very little for their cost: `MemoryCacheLincheckTest` and
  `BoundedLruMapLincheckTest` run `maxEntries = 10` against keys `1:3`, so eviction never fires,
  over operation bodies that are one `synchronized` block each. They are not *incapable* of
  failing — they would catch a `synchronized` being dropped or split wrongly — but that is the
  whole of their guarantee. (`MemoryCacheEvictionLincheckTest` already covers the interesting half
  for `MemoryCache`; `BoundedLruMap` has no eviction counterpart at all.) Meanwhile the code that
  *is* hand-rolled has no model-checking: `EpochFence.fence` does `keyEpochs[key] =
  (keyEpochs[key] ?: 0L) + 1L` — a non-atomic read-modify-write on a `ConcurrentHashMap`, correct
  today only because every one of its call sites holds `commitGuard`. That invariant *is* stated —
  `EpochFence`'s class KDoc has a **Locking** paragraph and `fence`/`fenceAll` each repeat "must run
  under the commit lock" — but nothing **enforces** it, which is the real gap. (An earlier revision
  of this entry said it was unstated; it isn't.) The other hand-rolled primitive is the active-key
  registry's CAS loops. Keep the baseline classes for their synchronization-removal coverage and add
  cases aimed at both.

  **The registry half is shipped.** Those loops lived inline in `RealAquifer` as
  `registerActive`/`unregisterActive`; they are now `ActiveKeyRegistry`, extracted for the same
  reason `EpochFence` was — so a Lincheck check drives the exact code, not an approximation — and
  covered by `ActiveKeyRegistryLincheckTest` under both the stress and model-checking strategies.

  Worth recording what that check taught, because it is a trap for the `EpochFence` half too: the
  first version also asserted linearizability over `snapshot()`/`keys()`, and Lincheck rejected it
  with a counterexample where one read saw a key registered concurrently and a *later read on the
  same thread* did not. That is not a lost update — those two views iterate the `ConcurrentHashMap`
  weakly and were never atomic across keys. The check belongs on the per-key state, which every
  update genuinely compare-and-sets; the multi-key views now say in prose that nothing needing an
  atomic cross-key view may be built on them. A verifier will happily "fail" on a property the code
  never claimed.
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
- [x] **Key the hydration guard on a commit-only generation counter** (shipped) — the guard shipped
  in 0.6 captured `sequencer.get()` before the off-lock persistence read and re-read under
  `commitGuard` if it moved. But hydration *itself* advances the sequencer (`load`/`loadAll`
  allocate a sequence for the entry they install), so the guard fired on far more than "a commit
  raced": two concurrent cold reads of *different* keys interfered — the first to take the lock saw
  an unchanged sequencer and hydrated directly, but its own allocation forced every later contender
  through the guarded re-read, so N concurrent cold reads cost N−1 extra reads, each performed while
  holding the commit lock, and for `loadAll` that re-read was the whole batch again
  (`store.readAll(epochs.keys)`) — exactly the shape of a cold start, and of the reconnect sweep.

  Fixed by giving commits their own counter, `commitGen`, that hydration does not advance, with a
  single `commitSequence()` allocator so the six commit sites cannot drift from it and a new one
  cannot silently miss it. Correctness was never at stake — the re-read is authoritative either way
  — so the win is purely the avoidable I/O, taken in the window where the commit lock is most
  contended.

  Two tests pin it, and they discriminate in opposite directions: with the guard keyed back on the
  sequencer, two concurrent cold reads of different keys record reads `[a, b, b]` instead of
  `[a, b]`; and a racing *fetch* commit (which does not move the epoch, unlike `put`/`invalidate`,
  and which must therefore still be caught) forces the re-read under both keyings — proving the
  guard was made accurate rather than merely quieter. *(M)*

## 0.6 — API ergonomics & polish

Small, high-frequency conveniences surfaced while building the feature set; each must keep
the existing fencing and single-flight guarantees.

- [ ] **Weight-bounded memory cache** — `memoryCache { maxEntries }` counts entries, so a store
  whose values are lists (a feed page, a search result, an order history under one key) holds a
  handful of multi-megabyte entries under a cap that reads as generous, and the only lever is a
  smaller count that also starves the small-value keys. Persistence already bounds by bytes; memory
  cannot. Add `maxWeight` with a `weigher { key, value -> Int }` — the `LruCache.sizeOf` and
  Caffeine shape — evicting least-recently-used until the total fits, counted in
  `CacheStats.evictions` exactly like count eviction, with `trimToSize(n)` unchanged (it trims by
  count; a `trimToWeight` can follow if anyone asks). An entry heavier than the whole budget is not
  retained, mirroring the file store's absolute `maxBytes`. *(S–M)*
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
  - **`revalidateOn` returns nothing.** A subscription ends only when the trigger completes or the
    store closes; a screen-scoped trigger cannot be detached, so a caller who wants that has to
    wrap the trigger in a flow they can complete themselves. Returning a handle (`DisposableHandle`,
    `Job`, or an `AutoCloseable`) is a return-type change — a binary break after 1.0, free before.
  - **Streams go quiet on `close()` rather than completing.** Documented and deliberate, and a
    footgun for the session-scoped store (log out, close, build a new one): a collector in any
    longer-lived scope stays suspended forever, and the KDoc's answer — cancel the collecting scope
    — assumes the collector knows the store is gone. Completing the flow (or failing it with
    `AquiferException`, matching what awaiting `get` callers receive) is a behaviour change that
    costs nothing now.
  - **Every additive member queued on this file lands on the same abstract interface.** `peek`
    (0.2), `purgeExpired` (0.4), `getAllStates` (this docket), tag invalidation (0.6): the
    implementation-stance bullet above is on the critical path of all four, and each one shipped
    before it is decided is another member `FakeAquifer` and every third-party implementor must
    already carry. Decide it first. (`AquiferEvents` and `SourceOfTruth` are not in this bind: their
    defaults compile to JVM default methods, so a new defaulted member there is binary-safe — see
    the observability item in 0.5 — which is what lets `deleteWrittenBefore` land on the SPI.)
  - **The locked data classes.** `FetchResult.Fresh`, `PersistedEntry` and `CacheStats` are
    `data class`es: a new field changes the constructor, `copy` and `componentN` at once, which BCV
    rightly reports as a break. Two items above want new fields (the two stale horizons in 0.3,
    the counters in 0.5).
    Either land them before the freeze, or decide now that these types stop being `data class`es (a
    plain class with a builder, or explicit `copy`) so 1.x can grow them. `HttpException` is exempt
    — a class can gain a defaulted secondary constructor — which is how `retryAfter` is planned.
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
