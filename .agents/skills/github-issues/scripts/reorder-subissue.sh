#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/common.sh"

[[ $# -eq 4 ]] || usage "reorder-subissue.sh PARENT_ISSUE_NUMBER CHILD_ISSUE_NUMBER --after|--before SIBLING_ISSUE_NUMBER"

parent_issue_number="$1"
child_issue_number="$2"
position_flag="$3"
sibling_issue_number="$4"

require_issue_number "$parent_issue_number" "PARENT_ISSUE_NUMBER"
require_issue_number "$child_issue_number" "CHILD_ISSUE_NUMBER"
require_issue_number "$sibling_issue_number" "SIBLING_ISSUE_NUMBER"

case "$position_flag" in
  --after) position_field="after_id" ;;
  --before) position_field="before_id" ;;
  *) usage "reorder-subissue.sh PARENT_ISSUE_NUMBER CHILD_ISSUE_NUMBER --after|--before SIBLING_ISSUE_NUMBER" ;;
esac

child_issue_rest_id="$(issue_rest_id "$child_issue_number")"
sibling_issue_rest_id="$(issue_rest_id "$sibling_issue_number")"

gh api -X PATCH "repos/{owner}/{repo}/issues/${parent_issue_number}/sub_issues/priority" \
  -H "$ACCEPT_HEADER" \
  -F "sub_issue_id=${child_issue_rest_id}" \
  -F "${position_field}=${sibling_issue_rest_id}" \
  --jq "$(issue_summary_filter)"

printf '\nVerified parent sub-issues:\n'
"${script_dir}/list-subissues.sh" "$parent_issue_number"
