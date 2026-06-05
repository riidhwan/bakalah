#!/usr/bin/env bash
set -euo pipefail

tag="${TAG:?TAG is required}"
target_sha="${TARGET_SHA:?TARGET_SHA is required}"
token="${RELEASE_TAG_GITHUB_TOKEN:?RELEASE_TAG_GITHUB_TOKEN is required}"
repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

if [[ "$target_sha" == "null" ]]; then
  echo "TARGET_SHA must point to the merged release revision"
  exit 1
fi

git config user.name "bakalah-release-bot"
git config user.email "actions@github.com"

git tag -a "$tag" -m "Bakalah $tag" "$target_sha"
git push "https://x-access-token:$token@github.com/$repository.git" "refs/tags/$tag"
