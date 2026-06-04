#!/usr/bin/env bash
set -euo pipefail

tag="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"

if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Release tags must use vMAJOR.MINOR.PATCH format, got: $tag"
  exit 1
fi

version="${tag#v}"
version_name="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -n 1)"

if [[ "$version" != "$version_name" ]]; then
  echo "Release tag $tag does not match app versionName $version_name"
  exit 1
fi

.github/scripts/extract-changelog-section.sh "$tag" release_body.md

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "tag=$tag"
    echo "version=$version"
  } >> "$GITHUB_OUTPUT"
fi
