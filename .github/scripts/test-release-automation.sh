#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
"$root/.github/scripts/test-release-publication-policy.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
ln -s "$root/.github" "$work/.github"
cd "$work"

tag=v1.2.3
files=(
  "bakalah-$tag.apk"
  "bakalah-arm64-v8a-$tag.apk"
  "bakalah-armeabi-v7a-$tag.apk"
  "bakalah-x86-$tag.apk"
  "bakalah-x86_64-$tag.apk"
)

if .github/scripts/verify-release-artifacts.sh "$tag" >/dev/null 2>&1; then
  echo "FAIL: missing artifact set was accepted"
  exit 1
fi

for file in "${files[@]}"; do
  printf 'test artifact %s\n' "$file" > "$file"
done

.github/scripts/create-release-checksums.sh "$tag" >/dev/null
[[ "$(wc -l < SHA256SUMS)" -eq 5 ]] || { echo "FAIL: checksum manifest is incomplete"; exit 1; }
sha256sum --check SHA256SUMS >/dev/null

printf '# Changelog\n' > CHANGELOG.md
if .github/scripts/extract-changelog-section.sh "$tag" release_body.md >/dev/null 2>&1; then
  echo "FAIL: missing changelog section was accepted"
  exit 1
fi

echo "Release automation tests passed"
