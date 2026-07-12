#!/usr/bin/env bash
set -euo pipefail

tag="${TAG:?TAG is required}"
target_sha="${TARGET_SHA:?TARGET_SHA is required}"
repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Invalid recovery tag: $tag"; exit 1; }
[[ "$target_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "TARGET_SHA must be a full commit SHA"; exit 1; }

ref_type="$(gh api "repos/$repository/git/ref/tags/$tag" --jq '.object.type')"
ref_sha="$(gh api "repos/$repository/git/ref/tags/$tag" --jq '.object.sha')"
[[ "$ref_type" == tag ]] || { echo "Recovery requires an existing annotated tag: $tag"; exit 1; }

tag_target="$(gh api "repos/$repository/git/tags/$ref_sha" --jq '.object.sha')"
[[ "$tag_target" == "$target_sha" ]] || { echo "$tag points to $tag_target, expected $target_sha"; exit 1; }
[[ "$(git rev-parse HEAD)" == "$target_sha" ]] || { echo "Checked-out tag does not resolve to $target_sha"; exit 1; }

version_name="$(bash .github/scripts/read-app-version-name.sh)"
[[ "$tag" == "v$version_name" ]] || { echo "$tag does not match app versionName $version_name"; exit 1; }
.github/scripts/extract-changelog-section.sh "$tag" /tmp/recovery-release-notes.md

if gh api "repos/$repository/releases/tags/$tag" --jq '.draft' > /tmp/recovery-draft-state 2>/dev/null; then
  [[ "$(cat /tmp/recovery-draft-state)" == true ]] || { echo "Published release $tag is immutable"; exit 1; }
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "tag=$tag"
    echo "target_sha=$target_sha"
  } >> "$GITHUB_OUTPUT"
fi

echo "Validated recovery for $tag at $target_sha"
