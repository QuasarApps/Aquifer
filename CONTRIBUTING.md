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

Requirements: JDK 17+ to run the build. CI builds and tests on JDK 17 and 21, and separately runs
four JVM modules' tests (`aquifer-core`, `aquifer-test`, `aquifer-persistence-file`,
`aquifer-okhttp`) on a JDK 11 launcher to prove the JVM-11 bytecode target actually executes on a
Java 11 runtime; `aquifer-persistence-sqldelight` is not in that job. You also need an Android SDK
for `:aquifer-android` (point
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
- **`aquifer-test` is a publishing module**, not an internal test fixture: `fakeAquifer`,
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
and `SIGNING_KEY_PASSWORD`. Bump `version` in `gradle.properties` and add a dated
`## [x.y.z]` section to `CHANGELOG.md` before tagging — the workflow refuses to publish when the
tag doesn't match the module versions, when the version is a `-SNAPSHOT`, or when the CHANGELOG
has no section for it.

After publishing, the workflow cuts a GitHub Release from that CHANGELOG section, marking a
version with a pre-release suffix (`1.0.0-rc1`) as a pre-release. The two ordering choices are
deliberate: the notes are extracted **before** the build, so a missing section fails while
failing is still free — a Maven Central publication cannot be undone — and the release is created
**last**, so a failed publication never announces a release with nothing behind it. Creating it is
why the `publish` job holds `contents: write` while the workflow default stays `contents: read`.

`version` lives in `gradle.properties` alone: Gradle applies it to every project, so a release
bump is one edit. Do **not** reintroduce a `version = ...` line in a module build file — the
gate checks each publishing module separately, so an override would fail the release rather
than ship silently.

That gate takes the modules it checks from the root `publishingModules` task, which lists every
subproject applying the `com.vanniktech.maven.publish` plugin — the same condition that decides
what `publishAndReleaseToMavenCentral` publishes. A new publishing module therefore joins the
gate the moment it applies the plugin, with nothing to keep in sync by hand, and the workflow
refuses to release if that list ever comes back empty rather than passing without checking
anything.
