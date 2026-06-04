#!/usr/bin/env bash
set -euo pipefail

variant="${1:?Usage: rename-release-apks.sh <release|foss> <tag>}"
tag="${2:?Usage: rename-release-apks.sh <release|foss> <tag>}"

case "$variant" in
  release)
    mv app/build/outputs/apk/release/app-universal-release-unsigned-signed.apk "bakalah-$tag.apk"
    mv app/build/outputs/apk/release/app-arm64-v8a-release-unsigned-signed.apk "bakalah-arm64-v8a-$tag.apk"
    mv app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned-signed.apk "bakalah-armeabi-v7a-$tag.apk"
    mv app/build/outputs/apk/release/app-x86-release-unsigned-signed.apk "bakalah-x86-$tag.apk"
    mv app/build/outputs/apk/release/app-x86_64-release-unsigned-signed.apk "bakalah-x86_64-$tag.apk"
    ;;
  foss)
    mv app/build/outputs/apk/foss/app-universal-foss-unsigned-signed.apk "bakalah-$tag-foss.apk"
    ;;
  *)
    echo "Unknown APK variant: $variant"
    exit 1
    ;;
esac
