#!/usr/bin/env bash

github_release_select_by_tag() {
  local tag="$1"
  local matches count
  matches="$(jq -cs --arg tag "$tag" 'add | map(select(.tag_name == $tag))')"
  count="$(jq 'length' <<< "$matches")"

  case "$count" in
    0)
      return 1
      ;;
    1)
      jq '.[0]' <<< "$matches"
      ;;
    *)
      echo "Multiple GitHub Releases exist for $tag" >&2
      return 2
      ;;
  esac
}

github_release_by_tag() {
  local repository="$1"
  local tag="$2"
  gh api --paginate "repos/$repository/releases?per_page=100" | github_release_select_by_tag "$tag"
}
