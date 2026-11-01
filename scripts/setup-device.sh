#!/usr/bin/env bash
set -euo pipefail
source /workspace/android-dev/env.sh
if [ ! -x "$ANDROID_HOME/emulator/emulator" ] || [ ! -f "$ANDROID_HOME/system-images/android-36/default/x86_64/system.img" ]; then
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" emulator 'system-images;android-36;default;x86_64'
fi
mkdir -p "$ANDROID_AVD_HOME/excerpt-aosp.avd"
if [ ! -f "$ANDROID_AVD_HOME/excerpt-aosp.ini" ]; then
    cat > "$ANDROID_AVD_HOME/excerpt-aosp.ini" <<EOF
avd.ini.encoding=UTF-8
path=$ANDROID_AVD_HOME/excerpt-aosp.avd
target=android-36
EOF
fi
if [ ! -f "$ANDROID_AVD_HOME/excerpt-aosp.avd/config.ini" ]; then
    cat > "$ANDROID_AVD_HOME/excerpt-aosp.avd/config.ini" <<'EOF'
avd.ini.encoding=UTF-8
AvdId=excerpt-aosp
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=3072
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=280
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.camera.back=emulated
hw.camera.front=none
hw.mainKeys=no
disk.dataPartition.size=4G
image.sysdir.1=system-images/android-36/default/x86_64/
tag.id=default
tag.display=AOSP
PlayStore.enabled=false
showDeviceFrame=no
skin.dynamic=yes
EOF
fi
printf 'Prepared Android 16 emulator: %s/excerpt-aosp.avd\n' "$ANDROID_AVD_HOME"
