<!--
Target `develop`, not `main` — `main` is release-only and releases are cut by pushing a `v*` tag.
Keep the PR to one concern; a small diff gets a better review than a broad one.
-->

## What & why

<!-- What changes, and the problem it solves. The *why* is the part reviewers cannot reconstruct
     from the diff. If it fixes an issue, link it. -->

## Verification

<!-- What you ran, and what it proved. For a behaviour change, say what a test would have caught
     had you not fixed it — a test that passes against the old code is not a regression guard. -->

## Checklist

- [ ] `./gradlew build` passes — it compiles, tests, runs detekt (with its bundled ktlint rules),
      and verifies the locked public API in one shot.
- [ ] Behaviour changes come with tests.
- [ ] **If the public API changed intentionally:** `./gradlew apiDump` re-run and the updated
      `*/api/*.api` files committed here, or `apiCheck` will fail.
- [ ] **If the public API grew:** a `CHANGELOG.md` entry under `[Unreleased]` and a README update,
      in this PR.
- [ ] New public symbols carry KDoc stating their contract — threading, failure behaviour,
      defaults. Explicit API mode means visibility modifiers are required anyway.
