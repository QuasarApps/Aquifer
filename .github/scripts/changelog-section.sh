#!/usr/bin/env bash
#
# Prints the CHANGELOG section for a release version to stdout, or fails if there isn't one.
#
# The release workflow runs this twice, in two different jobs and for two different reasons:
#
#   * the publish job runs it *before* the build, so a missing or misnamed section fails while
#     failing is still free — a Maven Central publication cannot be undone;
#   * the release job runs it to produce the notes it hands to `gh release create`. That job is
#     separate precisely so a transient GitHub API failure can be re-run without repeating the
#     publish, which immutable coordinates would reject.
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
