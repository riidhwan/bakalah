#!/usr/bin/env bash

release_require_draft() {
  local draft="$1"
  [[ "$draft" == true ]] || { echo "Published releases are immutable"; return 1; }
}

release_require_same_repository() {
  local repository="$1"
  local head_repository="$2"
  [[ "$head_repository" == "$repository" ]] || { echo "Release intent must come from the same repository"; return 1; }
}

release_require_full_sha() {
  local sha="$1"
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "Release target must be a full commit SHA"; return 1; }
}

release_require_expected_tag() {
  local expected_sha="$1"
  local actual_sha="$2"
  local object_type="$3"
  [[ "$object_type" == tag ]] || { echo "Release tags must be annotated"; return 1; }
  [[ "$actual_sha" == "$expected_sha" ]] || { echo "Release tag points to an unexpected revision"; return 1; }
}

release_asset_action() {
  local local_digest="$1"
  local remote_digest="${2:-}"
  if [[ -z "$remote_digest" ]]; then
    echo upload
  elif [[ "$remote_digest" == "$local_digest" ]]; then
    echo keep
  else
    echo replace
  fi
}

release_asset_is_expected() {
  local candidate="$1"
  shift
  local expected
  for expected in "$@"; do
    [[ "$candidate" == "$expected" ]] && return 0
  done
  return 1
}
