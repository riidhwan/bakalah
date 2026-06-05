#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/common.sh"

[[ $# -eq 1 ]] || usage "get-parent.sh CHILD_ISSUE_NUMBER"

child_issue_number="$1"
require_issue_number "$child_issue_number" "CHILD_ISSUE_NUMBER"

if ! output="$(gh api "repos/{owner}/{repo}/issues/${child_issue_number}/parent" \
  --jq "$(issue_summary_filter)" 2>&1)"; then
  if [[ "$output" == *"HTTP 404"* || "$output" == *"Not Found"* ]]; then
    printf 'null\n'
    exit 0
  fi

  printf '%s\n' "$output" >&2
  exit 1
fi

printf '%s\n' "$output"
