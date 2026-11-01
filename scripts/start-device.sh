#!/usr/bin/env bash
# Software emulation needs longer framework timeouts during initial boot.
set -euo pipefail
source /workspace/android-dev/env.sh
serial=emulator-5556
if ! adb devices | awk -v serial="$serial" '$1 == serial { found = 1 } END { exit !found }'; then
    nohup "$ANDROID_HOME/emulator/emulator" -avd excerpt-aosp -accel off -no-window \
        -gpu swiftshader -no-audio -no-boot-anim -no-snapshot -memory 3072 -cores 2 \
        -port 5556 > /tmp/excerpt-emulator.log 2>&1 &
fi
adb -s "$serial" wait-for-device
if [ "$(adb -s "$serial" shell getprop ro.hw_timeout_multiplier | tr -d '\r')" != 20 ]; then
    adb -s "$serial" root
    adb -s "$serial" wait-for-device
    adb -s "$serial" shell stop
    adb -s "$serial" shell setprop ro.hw_timeout_multiplier 20
    adb -s "$serial" shell start
fi
# Provision the headless AOSP image before waiting for the home activity.
deadline=$((SECONDS + 1800))
until adb -s "$serial" shell settings put global cached_apps_freezer disabled >/dev/null 2>&1; do
    if (( SECONDS > deadline )); then
        echo 'Android settings service did not become ready' >&2
        exit 1
    fi
    sleep 5
done
adb -s "$serial" shell settings put global device_provisioned 1
adb -s "$serial" shell settings put secure user_setup_complete 1
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
adb -s "$serial" shell settings put global cached_apps_freezer disabled
adb -s "$serial" shell svc power stayon true
adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
adb -s "$serial" shell wm dismiss-keyguard
echo 'Android 16 emulator is ready'
