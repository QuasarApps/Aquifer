#!/usr/bin/env bash
#
# Prints the CHANGELOG section for a release version to stdout, or fails if there isn't one.
#
# Two workflows call this, for two different reasons:
#
#   * `release.yml` runs it *before* the build, purely as a gate — a missing or misnamed section
#     fails the release while failing is still free, since a Maven Central publication cannot be
#     undone. Its output is discarded.
#   * `github-release.yml` runs it against the *tagged* checkout to produce the notes it hands to
#     `gh release create`, so the announcement carries the CHANGELOG as it shipped rather than as
#     `develop` looks later.
#
# They are separate workflows, not two jobs, because publication is staged and released by hand:
# chaining the announcement to the publish run would announce a deployment that is not yet public
# and could still be dropped. A consequence worth keeping: no GitHub API failure can ever force a
# re-publish, because the two never share a run.
#
# Keeping one implementation for both means the gate and the notes cannot disagree about what a
# section is.
#
# Usage: changelog-section.sh <version> [changelog-path]
set -euo pipefail

VERSION="${1:?usage: changelog-section.sh <version> [changelog-path]}"
CHANGELOG="${2:-CHANGELOG.md}"

# The heading is matched as a *literal* prefix, never as a regex. A version string is not
# regex-safe: '.' would match any character (so `## [1x0y0]` would satisfy a request for 1.0.0),
# and SemVer build metadata may contain '+', which is a quantifier.
SECTION=$(
  awk -v heading="## [$VERSION]" '
    BEGIN { n = length(heading) }
    !found && substr($0, 1, n) == heading { found = 1; next }
    # Stop at the next section heading, or at the link-reference footer.
    found && (substr($0, 1, 3) == "## " || $0 ~ /^\[[^]]+\]: /) { exit }
    found { print }
  ' "$CHANGELOG" | sed '/./,$!d'
)

if [ -z "${SECTION//[[:space:]]/}" ]; then
  echo "$CHANGELOG has no '## [$VERSION]' section, so a release would be announced with empty" >&2
  echo "notes. Add the section (or collapse [Unreleased] into it) before tagging." >&2
  exit 1
fi

printf '%s\n' "$SECTION"
