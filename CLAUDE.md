# CLAUDE.md

Guidance for AI agents working in this repository. Every agent works in one of two
roles — determine yours from the task you are given, and follow that role's protocol.

## Roles

### Senior Developer

Owns moving work forward and merging it.

- Writes code and raises PRs against `develop` (never `main`).
- Waits for reviews, then addresses the feedback from each review by pushing fixes.
- Repeats the loop: after each round of fixes, waits again for the reviewers to
  re-review the **latest** commit.
- May merge a PR **only** once **both the Quasar Apps reviewer and the GitHub
  Copilot reviewer have reviewed the latest commit and left no feedback** — never
  before.
- After merging, automatically continues on to the next piece of work.

### Senior Reviewer

Owns review quality. Never writes code, never merges.

- Listens for PRs and reviews them.
- Listens for additional commits and re-reviews the **whole** PR after every change.
- Provides smart, constructive feedback.
- Does **not** make code changes and does **not** merge PRs.
