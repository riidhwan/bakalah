#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: rename-release-apks.sh <tag>}"

mv app/build/outputs/apk/release/app-universal-release-unsigned-signed.apk "bakalah-$tag.apk"
mv app/build/outputs/apk/release/app-arm64-v8a-release-unsigned-signed.apk "bakalah-arm64-v8a-$tag.apk"
mv app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned-signed.apk "bakalah-armeabi-v7a-$tag.apk"
mv app/build/outputs/apk/release/app-x86-release-unsigned-signed.apk "bakalah-x86-$tag.apk"
mv app/build/outputs/apk/release/app-x86_64-release-unsigned-signed.apk "bakalah-x86_64-$tag.apk"
