#!/usr/bin/env bash
# Signing runs after Gradle has exited; never pass release secrets to Gradle.
set -euo pipefail
: "${ANDROID_HOME:?}"
: "${ANDROID_KEYSTORE_PATH:?}"
: "${ANDROID_KEYSTORE_PASSWORD:?}"
input=${1:?unsigned APK}
output=${2:?signed APK}
tools="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p "$(dirname "$output")"
aligned=$(mktemp --suffix=.apk)
trap 'rm -f "$aligned"' EXIT
"$tools/zipalign" -P 16 -f 4 "$input" "$aligned"
"$tools/apksigner" sign --ks "$ANDROID_KEYSTORE_PATH" --ks-key-alias excerpt-memo-release \
    --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEYSTORE_PASSWORD \
    --v4-signing-enabled false --out "$output" "$aligned"
"$tools/apksigner" verify --verbose --print-certs "$output"
"$tools/zipalign" -P 16 -c 4 "$output"
