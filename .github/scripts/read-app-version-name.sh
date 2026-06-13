#!/usr/bin/env bash
set -euo pipefail

build_file="${1:-app/build.gradle.kts}"

version_assignment="$(
  sed -n 's/^[[:space:]]*versionName = \(.*\)$/\1/p' "$build_file" |
    head -n 1 |
    sed 's/[[:space:]]*$//'
)"

if [[ -z "$version_assignment" ]]; then
  echo "Could not find versionName assignment in $build_file" >&2
  exit 1
fi

if [[ "$version_assignment" =~ ^\"([^\"]+)\"$ ]]; then
  echo "${BASH_REMATCH[1]}"
  exit 0
fi

if [[ ! "$version_assignment" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
  echo "Unsupported versionName assignment in $build_file: $version_assignment" >&2
  exit 1
fi

version_property="${version_assignment}"
version_name="$(
  sed -n "s/^[[:space:]]*private[[:space:]]\\+val[[:space:]]\\+$version_property[[:space:]]*= \\\"\\(.*\\)\\\"[[:space:]]*$/\\1/p" "$build_file" |
    head -n 1
)"

if [[ -z "$version_name" ]]; then
  echo "Could not resolve versionName property $version_property in $build_file" >&2
  exit 1
fi

echo "$version_name"
