#!/usr/bin/env bash
# Release gate (QD-26): the pushed tag must match the version in gradle.properties, and VERSION_CODE must be higher
# than at the previous release tag, or the phones that have the last release would refuse the update.
#
# Usage: scripts/check-release-version.sh <tag>          e.g. scripts/check-release-version.sh v0.2.0
# Needs the full git history and tags (actions/checkout with fetch-depth: 0).
set -euo pipefail

tag="${1:?usage: check-release-version.sh <tag>}"

prop() { # prop <name> [<git rev>]: read a property from gradle.properties, at a revision when one is given
  local name="$1" rev="${2:-}" text
  if [ -n "$rev" ]; then text="$(git show "$rev:gradle.properties")"; else text="$(cat gradle.properties)"; fi
  printf '%s\n' "$text" | sed -n "s/^${name}=//p" | tr -d '[:space:]' | head -n1
}

name="$(prop VERSION_NAME)"
code="$(prop VERSION_CODE)"
[ -n "$name" ] || { echo "::error::VERSION_NAME is missing from gradle.properties"; exit 1; }
case "$code" in ''|*[!0-9]*) echo "::error::VERSION_CODE is not a number: '$code'"; exit 1 ;; esac

if [ "$tag" != "v$name" ]; then
  echo "::error::Tag $tag does not match VERSION_NAME $name (expected tag v$name)."
  exit 1
fi

# The newest earlier release tag (v*) reachable from this commit's parent. Fossify's upstream tags have no "v" and
# are not ours, so they are not compared.
prev="$(git describe --tags --abbrev=0 --match 'v*' "$tag^" 2>/dev/null || true)"
if [ -z "$prev" ]; then
  echo "No earlier v* tag: first release, VERSION_CODE $code."
  exit 0
fi

prev_code="$(prop VERSION_CODE "$prev")"
case "$prev_code" in ''|*[!0-9]*) echo "::error::VERSION_CODE at $prev is not a number: '$prev_code'"; exit 1 ;; esac
if [ "$code" -le "$prev_code" ]; then
  echo "::error::VERSION_CODE $code is not higher than $prev_code at $prev."
  exit 1
fi
echo "OK: $tag matches VERSION_NAME $name; VERSION_CODE $code > $prev_code at $prev."
