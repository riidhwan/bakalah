#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/common.sh"

[[ $# -eq 1 ]] || usage "list-subissues.sh PARENT_ISSUE_NUMBER"

parent_issue_number="$1"
require_issue_number "$parent_issue_number" "PARENT_ISSUE_NUMBER"

gh api "repos/{owner}/{repo}/issues/${parent_issue_number}/sub_issues?per_page=100" \
  --jq ".[] | $(issue_summary_filter)"
