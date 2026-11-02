#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
tool_root=/workspace/android-dev
mkdir -p "$tool_root/bin" "$tool_root/sdk/cmdline-tools"
cd "$tool_root"
if [ ! -x "$tool_root/jdk-21.0.13+9/bin/javac" ]; then
    archive=OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz
    curl -fLSs --retry 2 "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/$archive" -o "$archive"
    printf 'd7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c  %s\n' "$archive" | sha256sum -c
    tar -xzf "$archive"
fi
if [ -f /etc/ssl/certs/java/cacerts ]; then
    cp /etc/ssl/certs/java/cacerts "$tool_root/jdk-21.0.13+9/lib/security/cacerts"
fi
if [ ! -x "$tool_root/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -fLSs --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip -o tools.zip
    printf '0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8  tools.zip\n' | sha256sum -c
    unzip -q tools.zip -d sdk/cmdline-tools
    mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest
fi
cat > "$tool_root/env.sh" <<'ENV'
export JAVA_HOME=/workspace/android-dev/jdk-21.0.13+9
export ANDROID_HOME=/workspace/android-dev/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME=/workspace/android-dev/user
export ANDROID_EMULATOR_HOME=/workspace/android-dev/user
export ANDROID_AVD_HOME=/workspace/android-dev/avd
export GRADLE_USER_HOME=/workspace/android-dev/gradle
export PATH="$JAVA_HOME/bin:/workspace/android-dev/bin:$ANDROID_HOME/platform-tools:$PATH"
ENV
source "$tool_root/env.sh"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0' platform-tools
test -f "$ANDROID_HOME/platforms/android-36/android.jar"
cd "$root"
printf '76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3  gradle/wrapper/gradle-wrapper.jar\n' | sha256sum -c
python3 scripts/verify-models.py
python3 scripts/setup-opencv-host.py
bash scripts/setup-device.sh
python3 scripts/gradle-cloud.py --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
