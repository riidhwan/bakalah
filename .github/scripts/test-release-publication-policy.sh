#!/usr/bin/env bash
set -euo pipefail

source .github/scripts/release-publication-policy.sh

failures=0

expect_success() {
  local name="$1"
  shift
  if ! "$@" >/dev/null; then
    echo "FAIL: $name"
    failures=$((failures + 1))
  fi
}

expect_failure() {
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "FAIL: $name"
    failures=$((failures + 1))
  fi
}

expect_output() {
  local name="$1"
  local expected="$2"
  shift 2
  local actual
  actual="$("$@")"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL: $name (expected $expected, got $actual)"
    failures=$((failures + 1))
  fi
}

sha="0123456789012345678901234567890123456789"
expect_success "same-repository intent" release_require_same_repository riidhwan/bakalah riidhwan/bakalah
expect_failure "fork release intent" release_require_same_repository riidhwan/bakalah contributor/bakalah
expect_success "full target SHA" release_require_full_sha "$sha"
expect_failure "abbreviated target SHA" release_require_full_sha 0123456
expect_success "matching annotated tag" release_require_expected_tag "$sha" "$sha" tag
expect_failure "conflicting tag target" release_require_expected_tag "$sha" "1123456789012345678901234567890123456789" tag
expect_failure "lightweight tag" release_require_expected_tag "$sha" "$sha" commit
expect_success "draft is repairable" release_require_draft true
expect_failure "published release is immutable" release_require_draft false
expect_output "missing asset uploads" upload release_asset_action sha256:local ""
expect_output "matching asset stays" keep release_asset_action sha256:local sha256:local
expect_output "mismatched asset is replaced" replace release_asset_action sha256:local sha256:remote
expect_success "known asset accepted" release_asset_is_expected app.apk app.apk SHA256SUMS
expect_failure "unexpected asset rejected" release_asset_is_expected notes.txt app.apk SHA256SUMS

if [[ "$failures" -ne 0 ]]; then
  echo "$failures release publication policy test(s) failed"
  exit 1
fi

echo "Release publication policy tests passed"
