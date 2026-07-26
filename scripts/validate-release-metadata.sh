#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

checked_in_version="$(sed -nE 's/^VERSION_NAME=(.+)$/\1/p' gradle.properties | head -n 1)"
requested_version="${1:-$checked_in_version}"

if [ -z "$checked_in_version" ]; then
  echo "Missing VERSION_NAME in gradle.properties." >&2
  exit 1
fi

if ! echo "$checked_in_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$'; then
  echo "VERSION_NAME must be semantic, for example 0.2.0." >&2
  exit 1
fi

if echo "$checked_in_version" | grep -Eqi 'SNAPSHOT'; then
  echo "VERSION_NAME must be a release version, not a snapshot." >&2
  exit 1
fi

if [ "$requested_version" != "$checked_in_version" ]; then
  echo "Release version $requested_version does not match gradle.properties VERSION_NAME $checked_in_version." >&2
  exit 1
fi

if ! awk -v heading="## v$checked_in_version" '
  $0 == heading || index($0, heading " - ") == 1 { found = 1 }
  END { exit(found ? 0 : 1) }
' CHANGELOG.md; then
  echo "CHANGELOG.md is missing a v$checked_in_version section." >&2
  exit 1
fi

if ! grep -Fq "org.logister:logister-android:$checked_in_version" README.md; then
  echo "README.md does not show the current Maven coordinate org.logister:logister-android:$checked_in_version." >&2
  exit 1
fi

echo "Release metadata is consistent for v$checked_in_version."
