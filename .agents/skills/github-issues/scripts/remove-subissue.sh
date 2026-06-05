#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/common.sh"

[[ $# -eq 2 ]] || usage "remove-subissue.sh PARENT_ISSUE_NUMBER CHILD_ISSUE_NUMBER"

parent_issue_number="$1"
child_issue_number="$2"
require_issue_number "$parent_issue_number" "PARENT_ISSUE_NUMBER"
require_issue_number "$child_issue_number" "CHILD_ISSUE_NUMBER"

child_issue_rest_id="$(issue_rest_id "$child_issue_number")"

gh api -X DELETE "repos/{owner}/{repo}/issues/${parent_issue_number}/sub_issue" \
  -H "$ACCEPT_HEADER" \
  -F "sub_issue_id=${child_issue_rest_id}" \
  --jq "$(issue_summary_filter)"

printf '\nVerified parent sub-issues:\n'
"${script_dir}/list-subissues.sh" "$parent_issue_number"

printf '\nVerified child parent:\n'
"${script_dir}/get-parent.sh" "$child_issue_number"
