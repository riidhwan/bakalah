#!/usr/bin/env bash
set -euo pipefail

variant="${1:?Usage: verify-release-artifacts.sh <release|foss|all> <tag>}"
tag="${2:?Usage: verify-release-artifacts.sh <release|foss|all> <tag>}"

verify_file() {
  local file="$1"

  if [[ ! -s "$file" ]]; then
    echo "Expected release artifact is missing or empty: $file"
    exit 1
  fi
}

verify_release_apks() {
  verify_file "bakalah-$tag.apk"
  verify_file "bakalah-arm64-v8a-$tag.apk"
  verify_file "bakalah-armeabi-v7a-$tag.apk"
  verify_file "bakalah-x86-$tag.apk"
  verify_file "bakalah-x86_64-$tag.apk"
}

verify_foss_apk() {
  verify_file "bakalah-$tag-foss.apk"
}

case "$variant" in
  release)
    verify_release_apks
    ;;
  foss)
    verify_foss_apk
    ;;
  all)
    verify_release_apks
    verify_foss_apk
    ;;
  *)
    echo "Unknown artifact set: $variant"
    exit 1
    ;;
esac
