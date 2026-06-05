#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/common.sh"

[[ $# -eq 1 ]] || usage "list-blocking.sh ISSUE_NUMBER"

issue_number="$1"
require_issue_number "$issue_number" "ISSUE_NUMBER"

gh api "repos/{owner}/{repo}/issues/${issue_number}/dependencies/blocking?per_page=100" \
  --jq ".[] | $(issue_summary_filter)"
