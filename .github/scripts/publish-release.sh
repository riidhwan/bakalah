#!/usr/bin/env bash
set -euo pipefail

tag="${TAG:?TAG is required}"
target_sha="${TARGET_SHA:?TARGET_SHA is required}"
repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
notes_file="${RELEASE_NOTES_FILE:-release_body.md}"

expected_assets=(
  "bakalah-$tag.apk"
  "bakalah-arm64-v8a-$tag.apk"
  "bakalah-armeabi-v7a-$tag.apk"
  "bakalah-x86-$tag.apk"
  "bakalah-x86_64-$tag.apk"
  "SHA256SUMS"
)

source .github/scripts/release-publication-policy.sh

fail() { echo "$*"; exit 1; }
api() { gh api "$@"; }
local_digest() { sha256sum "$1" | cut -d ' ' -f 1; }
release_json() { api "repos/$repository/releases/tags/$tag"; }

tag_target() {
  local ref_type ref_sha
  ref_type="$(api "repos/$repository/git/ref/tags/$tag" --jq '.object.type')"
  ref_sha="$(api "repos/$repository/git/ref/tags/$tag" --jq '.object.sha')"
  [[ "$ref_type" == tag ]] || fail "Existing release tag must be annotated: $tag"
  api "repos/$repository/git/tags/$ref_sha" --jq '.object.sha'
}

ensure_annotated_tag() {
  if api "repos/$repository/git/ref/tags/$tag" >/dev/null 2>&1; then
    local existing_target
    existing_target="$(tag_target)"
    release_require_expected_tag "$target_sha" "$existing_target" tag || fail "Release tag $tag is invalid"
    return
  fi

  local tag_object
  tag_object="$(api --method POST "repos/$repository/git/tags" -f tag="$tag" -f message="Bakalah $tag" -f object="$target_sha" -f type=commit --jq '.sha')"
  api --method POST "repos/$repository/git/refs" -f ref="refs/tags/$tag" -f sha="$tag_object" >/dev/null
}

ensure_draft_release() {
  if release_json >/dev/null 2>&1; then
    release_require_draft "$(release_json | jq -r '.draft')" || fail "Published release $tag is immutable"
    [[ "$(release_json | jq -r '.target_commitish')" == "$target_sha" ]] || fail "Release $tag targets an unexpected revision"
    [[ "$(release_json | jq -r '.body')" == "$(cat "$notes_file")" ]] || fail "Draft release notes differ from the reviewed changelog"
    return
  fi

  gh release create "$tag" --repo "$repository" --draft --title "Bakalah $tag" --notes-file "$notes_file" --target "$target_sha"
}

reconcile_assets() {
  local remote name asset_id remote_digest digest action
  remote="$(release_json)"
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    release_asset_is_expected "$name" "${expected_assets[@]}" || fail "Draft release contains unexpected asset: $name"
  done < <(jq -r '.assets[].name' <<< "$remote")

  for name in "${expected_assets[@]}"; do
    [[ -s "$name" ]] || fail "Expected release artifact is missing or empty: $name"
    digest="sha256:$(local_digest "$name")"
    remote_digest="$(jq -r --arg name "$name" '.assets[] | select(.name == $name) | .digest // empty' <<< "$remote")"
    action="$(release_asset_action "$digest" "$remote_digest")"
    [[ "$action" == keep ]] && continue
    asset_id="$(jq -r --arg name "$name" '.assets[] | select(.name == $name) | .id' <<< "$remote")"
    if [[ -n "$asset_id" ]]; then
      api --method DELETE "repos/$repository/releases/assets/$asset_id" >/dev/null
    fi
    gh release upload "$tag" "$name" --repo "$repository"
    remote="$(release_json)"
  done
}

verify_remote_release() {
  local remote name expected_digest remote_digest size state
  remote="$(release_json)"
  [[ "$(jq -r '.draft' <<< "$remote")" == true ]] || fail "Release became public before verification"
  [[ "$(jq -r '.target_commitish' <<< "$remote")" == "$target_sha" ]] || fail "Release target changed during publication"
  [[ "$(jq -r '.body' <<< "$remote")" == "$(cat "$notes_file")" ]] || fail "Release notes changed during publication"
  [[ "$(jq '.assets | length' <<< "$remote")" -eq "${#expected_assets[@]}" ]] || fail "Remote release does not contain the exact artifact set"

  for name in "${expected_assets[@]}"; do
    expected_digest="sha256:$(local_digest "$name")"
    remote_digest="$(jq -r --arg name "$name" '.assets[] | select(.name == $name) | .digest // empty' <<< "$remote")"
    size="$(jq -r --arg name "$name" '.assets[] | select(.name == $name) | .size // 0' <<< "$remote")"
    state="$(jq -r --arg name "$name" '.assets[] | select(.name == $name) | .state // empty' <<< "$remote")"
    [[ "$remote_digest" == "$expected_digest" ]] || fail "Remote digest mismatch for $name"
    [[ "$size" -gt 0 ]] || fail "Remote asset is empty: $name"
    [[ "$state" == uploaded ]] || fail "Remote asset upload is incomplete: $name"
  done
}

ensure_annotated_tag
ensure_draft_release
reconcile_assets
verify_remote_release
gh release edit "$tag" --repo "$repository" --draft=false
echo "Published immutable release $tag at $target_sha"
