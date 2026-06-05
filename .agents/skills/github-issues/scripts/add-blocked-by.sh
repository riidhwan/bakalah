#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/common.sh"

[[ $# -eq 2 ]] || usage "add-blocked-by.sh ISSUE_NUMBER BLOCKING_ISSUE_NUMBER"

issue_number="$1"
blocking_issue_number="$2"
require_issue_number "$issue_number" "ISSUE_NUMBER"
require_issue_number "$blocking_issue_number" "BLOCKING_ISSUE_NUMBER"

blocking_issue_rest_id="$(issue_rest_id "$blocking_issue_number")"

gh api -X POST "repos/{owner}/{repo}/issues/${issue_number}/dependencies/blocked_by" \
  -H "$ACCEPT_HEADER" \
  -F "issue_id=${blocking_issue_rest_id}" \
  --jq "$(issue_summary_filter)"

printf '\nVerified issues blocking #%s:\n' "$issue_number"
"${script_dir}/list-blocked-by.sh" "$issue_number"

printf '\nVerified issues blocked by #%s:\n' "$blocking_issue_number"
"${script_dir}/list-blocking.sh" "$blocking_issue_number"
