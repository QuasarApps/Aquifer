# Contributing

Thanks for your interest in Aquifer!

## Building

```bash
./gradlew build                      # compile, test, static analysis, and public API verification
./gradlew detekt                     # static analysis alone (detekt + ktlint formatting rules)
./gradlew :sample:run                # runnable tour of the library
./gradlew dokkaGenerate              # aggregated API docs in build/dokka/
./gradlew :aquifer-core:lincheckTest # Lincheck concurrency tests (slow; not part of `check`)
```

Requirements: JDK 17+ to run the build. CI builds and tests on JDK 17 and 21, and separately
runs the JVM modules' tests on a JDK 11 launcher to prove the JVM-11 bytecode target actually
executes on a Java 11 runtime. You also need an Android SDK for `:aquifer-android` (point
`local.properties`' `sdk.dir` or `ANDROID_HOME` at it; compileSdk 35). The Gradle wrapper
handles everything else.

## Project conventions

- **Explicit API mode** is on: every public symbol needs an explicit visibility modifier and
  should carry KDoc that states its contract (threading, failure behaviour, defaults).
- **Public API surface is locked** by the binary-compatibility validator. `./gradlew build`
  fails on any signature change; if the change is intentional, regenerate the dumps with
  `./gradlew apiDump` and commit the updated `api/*.api` files in the same PR.
- **Tests must be deterministic.** Use `kotlinx-coroutines-test` (`runTest`), inject the
  store's `scope` and `WallClock`, and assert stream emissions with Turbine. Note that
  `runTest` only drives `backgroundScope` work while the test coroutine is suspended — see
  `TestHelpers.kt`.
- **`aquifer-test` is a published module**, not an internal test fixture: `fakeAquifer`,
  `FakeClock`, and `settle()` are locked public API that downstream test suites will depend on,
  so changing their behaviour is a user-visible change like any other.
- **Concurrency tests run separately.** `./gradlew :aquifer-core:lincheckTest` runs the
  Lincheck suite (the tests tagged `lincheck`). Model checking takes minutes, so those tests
  are excluded from `test` — and therefore from `check`/`build` — to keep `./gradlew build`
  fast; CI runs them in a dedicated `lincheck` job. Run the task locally when you touch shared
  mutable engine state.
- **Static analysis is part of `check`**: detekt (with ktlint formatting rules) runs on every
  module against `config/detekt/detekt.yml` with zero tolerated issues. Prefer fixing
  findings; the few deliberate engine patterns that trip generic rules carry a local
  `@Suppress` with a justification comment — follow that precedent rather than widening the
  config.
- **Generated files** (Gradle wrapper scripts) are upstream-owned; don't hand-edit them.

## Pull requests

- **Target `develop`**, the integration branch — every PR bases off it, never off `main`.
  `main` is release-only: releases are cut by pushing a `v*` tag (see below).
- Keep PRs focused on one concern; include tests for behaviour changes, and — whenever the
  public API grows — both a README update **and** a `CHANGELOG.md` entry under `[Unreleased]`,
  in the same PR. The changelog is where a user learns what a release added, so API that lands
  without an entry is invisible until someone diffs the `api/*.api` dumps.
- CI must be green: build, tests, and `apiCheck` all run on every PR.

## Releasing (maintainers)

Releases are cut by tagging: pushing a `v*` tag runs the `release` workflow, which publishes
to Maven Central via the Central Portal. It requires these repository secrets:
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` (ASCII-armored PGP),
and `SIGNING_KEY_PASSWORD`. Bump `version` in the module build files and update
`CHANGELOG.md` before tagging — the workflow refuses to publish when the tag doesn't match
the module versions or when the version is a `-SNAPSHOT`.

That version gate enumerates the modules it checks by hand, in a shell loop in
`.github/workflows/release.yml`, while `publishAndReleaseToMavenCentral` publishes every
module declaring `publishToMavenCentral()`. The two lists drift silently: a module missing
from the loop is still published, just without its version ever being checked against the
tag. Add every new publishing module to that loop in the same PR that starts publishing it —
a roadmap item tracks deriving the list from the publishing modules instead.
