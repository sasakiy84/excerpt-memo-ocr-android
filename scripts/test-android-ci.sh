#!/usr/bin/env bash
set -euo pipefail
: "${RUNNER_TEMP:?}"
: "${ANDROID_HOME:?}"
export ANDROID_AVD_HOME="$RUNNER_TEMP/excerpt-avd"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
test -c /dev/kvm || { echo 'Hardware virtualization is required for CI device tests'; exit 1; }
sudo chmod 666 /dev/kvm
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" emulator 'system-images;android-36;google_apis;x86_64'
mkdir -p "$ANDROID_AVD_HOME/excerpt.avd" "$RUNNER_TEMP/device-results"
cat > "$ANDROID_AVD_HOME/excerpt.ini" <<EOF
avd.ini.encoding=UTF-8
path=$ANDROID_AVD_HOME/excerpt.avd
target=android-36
EOF
cat > "$ANDROID_AVD_HOME/excerpt.avd/config.ini" <<'EOF'
avd.ini.encoding=UTF-8
AvdId=excerpt
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=3072
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.camera.back=emulated
hw.camera.front=none
hw.mainKeys=no
disk.dataPartition.size=4G
image.sysdir.1=system-images/android-36/google_apis/x86_64/
tag.id=google_apis
tag.display=Google APIs
PlayStore.enabled=false
showDeviceFrame=no
skin.dynamic=yes
EOF
"$ANDROID_HOME/emulator/emulator" -avd excerpt -accel on -no-window -gpu swiftshader \
    -no-audio -no-boot-anim -no-snapshot -memory 3072 -cores 2 -port 5554 \
    > "$RUNNER_TEMP/device-results/emulator.log" 2>&1 &
emulator_pid=$!
trap 'adb -s emulator-5554 emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null || true' EXIT
adb -s emulator-5554 wait-for-device
deadline=$((SECONDS + 600))
until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do
    if (( SECONDS > deadline )); then cat "$RUNNER_TEMP/device-results/emulator.log"; exit 1; fi
    sleep 3
done
adb -s emulator-5554 shell input keyevent 82
adb -s emulator-5554 shell wm dismiss-keyguard
adb -s emulator-5554 shell settings put global window_animation_scale 0
adb -s emulator-5554 shell settings put global transition_animation_scale 0
adb -s emulator-5554 shell settings put global animator_duration_scale 0
python3 scripts/run-device-tests.py | tee "$RUNNER_TEMP/device-results/tests.log"
for screen in camera page text; do
    adb -s emulator-5554 exec-out run-as com.sasakiy84.excerptmemo cat "files/test-$screen.png" \
        > "$RUNNER_TEMP/device-results/$screen.png"
done
