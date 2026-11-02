#!/usr/bin/env bash
set -euo pipefail
: "${RUNNER_TEMP:?}"
: "${GITHUB_ENV:?}"
export ANDROID_HOME="$RUNNER_TEMP/excerpt-sdk"
export ANDROID_USER_HOME="$RUNNER_TEMP/excerpt-android-user"
mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -fLSs --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip -o "$RUNNER_TEMP/android-tools.zip"
printf '0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8  %s\n' "$RUNNER_TEMP/android-tools.zip" | sha256sum -c
unzip -q "$RUNNER_TEMP/android-tools.zip" -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0'
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\nANDROID_USER_HOME=%s\n' "$ANDROID_HOME" "$ANDROID_HOME" "$ANDROID_USER_HOME" >> "$GITHUB_ENV"
