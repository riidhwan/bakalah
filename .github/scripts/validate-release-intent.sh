#!/usr/bin/env bash
set -euo pipefail

branch="${1:?Usage: validate-release-intent.sh <release/MAJOR.MINOR.PATCH>}"
target_sha="${TARGET_SHA:?TARGET_SHA is required}"
head_repository="${RELEASE_HEAD_REPOSITORY:?RELEASE_HEAD_REPOSITORY is required}"
repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

source .github/scripts/release-publication-policy.sh
source .github/scripts/github-release.sh

release_require_same_repository "$repository" "$head_repository"
release_require_full_sha "$target_sha"

.github/scripts/validate-release-branch.sh "$branch"

if ! git merge-base --is-ancestor "$target_sha" origin/main; then
  echo "Release target is not reachable from origin/main: $target_sha"
  exit 1
fi

version="${branch#release/}"
tag="v$version"

if ref_json="$(gh api "repos/$repository/git/ref/tags/$tag" 2>/dev/null)"; then
  ref_type="$(jq -r '.object.type' <<< "$ref_json")"
  ref_sha="$(jq -r '.object.sha' <<< "$ref_json")"
  [[ "$ref_type" == tag ]] || { echo "Existing release tag must be annotated: $tag"; exit 1; }
  existing_target="$(gh api "repos/$repository/git/tags/$ref_sha" --jq '.object.sha')"
  [[ "$existing_target" == "$target_sha" ]] || { echo "$tag points to $existing_target, expected $target_sha"; exit 1; }
fi

if release="$(github_release_by_tag "$repository" "$tag")"; then
  [[ "$(jq -r '.draft' <<< "$release")" == true ]] || { echo "Published release $tag is immutable"; exit 1; }
else
  status=$?
  [[ "$status" -eq 1 ]] || exit "$status"
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "version=$version"
    echo "tag=$tag"
    echo "target_sha=$target_sha"
  } >> "$GITHUB_OUTPUT"
fi

echo "Validated release intent for $tag at $target_sha"
