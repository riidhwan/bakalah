#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: create-release-checksums.sh <tag>}"
.github/scripts/verify-release-artifacts.sh all "$tag"

files=(
  "bakalah-$tag.apk"
  "bakalah-$tag-foss.apk"
  "bakalah-arm64-v8a-$tag.apk"
  "bakalah-armeabi-v7a-$tag.apk"
  "bakalah-x86-$tag.apk"
  "bakalah-x86_64-$tag.apk"
)

sha256sum "${files[@]}" > SHA256SUMS
sha256sum --check SHA256SUMS
