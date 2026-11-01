#!/usr/bin/env bash
# Software emulation needs longer framework timeouts during initial boot.
set -euo pipefail
source /workspace/android-dev/env.sh
serial=emulator-5554
if ! adb devices | awk -v serial="$serial" '$1 == serial { found = 1 } END { exit !found }'; then
    nohup "$ANDROID_HOME/emulator/emulator" -avd excerpt -accel off -no-window \
        -gpu swiftshader -no-audio -no-boot-anim -no-snapshot -memory 3072 -cores 2 \
        -port 5554 > /tmp/excerpt-emulator.log 2>&1 &
fi
adb -s "$serial" wait-for-device
if [ "$(adb -s "$serial" shell getprop ro.hw_timeout_multiplier | tr -d '\r')" != 20 ]; then
    adb -s "$serial" root
    adb -s "$serial" wait-for-device
    adb -s "$serial" shell stop
    adb -s "$serial" shell setprop ro.hw_timeout_multiplier 20
    adb -s "$serial" shell start
fi
deadline=$((SECONDS + 1800))
until [ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do
    if (( SECONDS > deadline )); then
        echo 'Android boot timed out; inspect /tmp/excerpt-emulator.log and adb logcat' >&2
        exit 1
    fi
    sleep 5
done
adb -s "$serial" shell settings put global watchdog_timeout_millis 60000
adb -s "$serial" shell settings put global window_animation_scale 0
adb -s "$serial" shell settings put global transition_animation_scale 0
adb -s "$serial" shell settings put global animator_duration_scale 0
echo 'Android 16 emulator is ready'
