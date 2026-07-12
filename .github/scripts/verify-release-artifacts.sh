#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: verify-release-artifacts.sh <tag>}"

verify_file() {
  local file="$1"

  if [[ ! -s "$file" ]]; then
    echo "Expected release artifact is missing or empty: $file"
    exit 1
  fi
}

verify_file "bakalah-$tag.apk"
verify_file "bakalah-arm64-v8a-$tag.apk"
verify_file "bakalah-armeabi-v7a-$tag.apk"
verify_file "bakalah-x86-$tag.apk"
verify_file "bakalah-x86_64-$tag.apk"
