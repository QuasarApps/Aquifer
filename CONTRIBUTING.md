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
- **Expand the collapsed part of an automated review.** Copilot files some findings as *"comments
  suppressed due to low confidence"*. Those live only inside a `<details>` block in the review body:
  they create **no review thread**, so they cannot be resolved, never appear as outstanding, and
  leave nothing behind but their text inside that one review body. Everything filed as a thread is
  tracked by GitHub whether or not anyone is diligent; these are tracked only by someone remembering
  to open the `<details>`. Triage them like any other comment.

  The label is not a good guide to whether they matter. On #80 all four suppressed findings were
  correct, and the most consequential comment in that PR was among them — it caught documentation
  telling readers to call `invalidate(key)` in response to an external write, which would have
  *deleted* the value being reacted to. Plausible reason for the mismatch: confidence appears
  calibrated for code review, while a claim about runtime behaviour in prose needs the KDoc and the
  implementation cross-referenced before it can be judged.

## Releasing (maintainers)

**A published Maven Central version can never be deleted, overwritten or amended.** Every choice
below follows from that: publishing is irreversible, everything around it is retryable.

Releasing takes four steps, two of them deliberately manual.

1. **Prepare, on `develop`.** Bump `version` in `gradle.properties` and add a dated
   `## [x.y.z]` section to `CHANGELOG.md`, in the same PR.
2. **Promote to `main`.** `main` is release-only and should be a fast-forward of `develop`, so the
   tagged commit is one CI has already validated:
   ```bash
   git checkout main && git merge --ff-only develop && git push origin main
   ```
   Nothing enforces this — the release workflow triggers on any `v*` tag on any commit — so tagging
   `develop` directly would publish perfectly well and leave `main` permanently behind. Promote
   first.
3. **Tag.** `git tag v0.1.0 && git push origin v0.1.0` runs the `release` workflow, which verifies
   the tag against every publishing module, verifies the CHANGELOG has a matching section, builds,
   and **stages** the deployment on the Central Portal. It requires these repository secrets:
   `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` (ASCII-armored PGP), and
   `SIGNING_KEY_PASSWORD` — the first two being a Central Portal *user token*, not login
   credentials. The workflow refuses to publish when the tag doesn't match the module versions, when
   the version is a `-SNAPSHOT`, or when the CHANGELOG has no section for it.
4. **Publish, then announce.** Review the staged deployment at
   [central.sonatype.com](https://central.sonatype.com/publishing/deployments) and click **Publish**
   — or **Drop** it, at no cost, if something is wrong. Once it reads `PUBLISHED`, run the
   **Cut a GitHub Release** workflow with the tag to announce it.

### Why steps 3 and 4 are split

- **The portal click is the last reversible moment.** `publishToMavenCentral` stages without
  publishing, so a bad build can still be dropped for free. Automating it away
  (`publishAndReleaseToMavenCentral`) removes the only checkpoint that exists — reasonable once the
  pipeline has proven itself, but it is a one-word change that should be made knowingly.
- **The GitHub Release is a separate, manually dispatched workflow** rather than a
  `needs: publish` job. Chained to the staged upload it would announce a version nobody can resolve
  and that might still be dropped; keeping it manual is what makes the announcement mean "this is
  downloadable". It is also why a transient GitHub API failure can never force a re-publish: the two
  never share a run.
- **The CHANGELOG section is verified before the build**, by
  `.github/scripts/changelog-section.sh`, so a missing or misnamed section fails while failing is
  still free. The release workflow reads it to validate; the announce workflow reads it *at the
  tagged commit* to produce the notes, so they are the notes that shipped.
- **`contents: write` is held only by the announce workflow**, and every checkout uses
  `persist-credentials: false` so no token lingers in `.git/config` for later build or publish
  commands to inherit.

The extraction script matches the heading as a literal prefix rather than a regex — a version is
not regex-safe (`.` matches any character, and SemVer build metadata may contain `+`) — and stops
at the next `##` heading or the link-reference footer.

`version` lives in `gradle.properties` alone: Gradle applies it to every project, so a release
bump is one edit. Do **not** reintroduce a `version = ...` line in a module build file — the
gate checks each publishing module separately, so an override would fail the release rather
than ship silently.

That gate takes the modules it checks from the root `publishingModules` task, which lists every
subproject applying the `com.vanniktech.maven.publish` plugin — the same condition that decides
what `publishToMavenCentral` uploads. A new publishing module therefore joins the
gate the moment it applies the plugin, with nothing to keep in sync by hand, and the workflow
refuses to release if that list ever comes back empty rather than passing without checking
anything.
