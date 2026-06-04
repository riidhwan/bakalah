#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: prepare-release-notes.sh <tag>}"

.github/scripts/extract-changelog-section.sh "$tag" release_body.md

cat >> release_body.md <<EOF

> [!TIP]
>
> ### If you are unsure which version to download then go with \`bakalah-$tag.apk\`
EOF
