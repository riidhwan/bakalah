#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: validate-release-branch.sh <release/MAJOR.MINOR.PATCH> [--check-remote]"
}

branch="${1:-}"
check_remote=false

if [[ -z "$branch" ]]; then
  usage
  exit 1
fi

shift
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --check-remote)
      check_remote=true
      ;;
    *)
      usage
      exit 1
      ;;
  esac
  shift
done

branch="${branch#refs/heads/}"

if [[ ! "$branch" =~ ^release/([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  echo "Release branches must use release/MAJOR.MINOR.PATCH format, got: $branch"
  exit 1
fi

version="${BASH_REMATCH[1]}"
tag="v$version"
version_name="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -n 1)"

if [[ "$version" != "$version_name" ]]; then
  echo "Release branch $branch does not match app versionName $version_name"
  exit 1
fi

release_body="$(mktemp)"
trap 'rm -f "$release_body"' EXIT

.github/scripts/extract-changelog-section.sh "$tag" "$release_body"

if [[ "$check_remote" == true ]]; then
  if git ls-remote --exit-code --tags origin "refs/tags/$tag" > /dev/null 2>&1; then
    echo "Release tag already exists: $tag"
    exit 1
  fi

  if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
    echo "GITHUB_REPOSITORY is required with --check-remote"
    exit 1
  fi

  if [[ -z "${GH_TOKEN:-}" ]]; then
    echo "GH_TOKEN is required with --check-remote"
    exit 1
  fi

  status="$(
    curl \
      --silent \
      --show-error \
      --output /tmp/release-response.json \
      --write-out "%{http_code}" \
      --header "Accept: application/vnd.github+json" \
      --header "Authorization: Bearer $GH_TOKEN" \
      --header "X-GitHub-Api-Version: 2022-11-28" \
      "https://api.github.com/repos/$GITHUB_REPOSITORY/releases/tags/$tag"
  )"

  case "$status" in
    200)
      echo "GitHub Release already exists: $tag"
      exit 1
      ;;
    404)
      ;;
    *)
      echo "Could not check GitHub Release for $tag; GitHub API returned HTTP $status"
      cat /tmp/release-response.json
      exit 1
      ;;
  esac
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "version=$version"
    echo "tag=$tag"
  } >> "$GITHUB_OUTPUT"
fi

echo "Validated release branch $branch for tag $tag"
