#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: extract-changelog-section.sh <tag> <output-file>}"
output_file="${2:?Usage: extract-changelog-section.sh <tag> <output-file>}"

awk -v tag="$tag" '
  $0 == "## [" tag "]" || index($0, "## [" tag "] - ") == 1 {
    found = 1
    next
  }

  found && /^## \[/ {
    exit
  }

  found {
    print
  }

  END {
    if (!found) {
      exit 1
    }
  }
' CHANGELOG.md > "$output_file"

if [[ ! -s "$output_file" ]]; then
  echo "CHANGELOG.md section for $tag is empty or missing"
  exit 1
fi
