#!/usr/bin/env bash
set -euo pipefail

readonly ACCEPT_HEADER='Accept: application/vnd.github+json'

usage() {
  printf 'Usage: %s\n' "$1" >&2
  exit 2
}

require_issue_number() {
  local value="$1"
  local name="$2"

  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    printf '%s must be a GitHub issue number, got: %s\n' "$name" "$value" >&2
    exit 2
  fi
}

issue_rest_id() {
  local issue_number="$1"

  require_issue_number "$issue_number" "issue_number"
  gh api "repos/{owner}/{repo}/issues/${issue_number}" --jq '.id'
}

issue_summary_filter() {
  printf '{number, id, title, state, html_url}'
}
