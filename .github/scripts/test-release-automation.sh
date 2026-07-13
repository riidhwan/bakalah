#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
"$root/.github/scripts/test-release-publication-policy.sh"
source "$root/.github/scripts/github-release.sh"

restore_count="$(grep -c 'git checkout origin/main -- .github/scripts' "$root/.github/workflows/release-recovery.yml")"
[[ "$restore_count" -eq 3 ]] || { echo "FAIL: every recovery job must restore current release automation"; exit 1; }
grep -q 'https://uploads.github.com/repos/' "$root/.github/scripts/publish-release.sh" || { echo "FAIL: release assets must use the uploads.github.com API"; exit 1; }
if grep -q 'api.uploads.github.com' "$root/.github/scripts/publish-release.sh"; then
  echo "FAIL: invalid api.uploads.github.com host is present"
  exit 1
fi

draft='{"id":352835133,"tag_name":"v0.38.0","draft":true}'
published='{"id":300000000,"tag_name":"v0.37.0","draft":false}'
selected="$(printf '[%s,%s]\n' "$draft" "$published" | github_release_select_by_tag v0.38.0)"
[[ "$(jq -r '.id' <<< "$selected")" == 352835133 ]] || { echo "FAIL: draft release was not found by tag"; exit 1; }
if printf '[%s]\n' "$published" | github_release_select_by_tag v0.38.0 >/dev/null; then
  echo "FAIL: missing release was reported as present"
  exit 1
fi
if printf '[%s,%s]\n' "$draft" "$draft" | github_release_select_by_tag v0.38.0 >/dev/null 2>&1; then
  echo "FAIL: duplicate draft releases were accepted"
  exit 1
fi

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
