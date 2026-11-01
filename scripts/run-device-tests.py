#!/usr/bin/env python3
"""Run the same AndroidJUnitRunner suite through adb on slow software emulators."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--expected-tests", type=int, default=6)
parser.add_argument("--reuse-app", action="store_true", help="Reuse the matching debug APK already installed on this device")
parser.add_argument("--class-name", help="Select an AndroidJUnit test class")
parser.add_argument("--no-ui-automation", action="store_true", help="For tests that do not access UI automation")
arguments = parser.parse_args()
root = Path(__file__).resolve().parents[1]
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
device = [adb, "-s", arguments.serial]
boot = subprocess.check_output([*device, "shell", "getprop", "sys.boot_completed"], text=True, timeout=120).strip()
if boot != "1":
    sys.exit("Wait for Android boot to complete before running tests")
apks = [root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
if not arguments.reuse_app:
    apks.insert(0, root / "app/build/outputs/apk/debug/app-debug.apk")
for apk in apks:
    if not apk.is_file():
        sys.exit("Build assembleDebug assembleDebugAndroidTest first")
    # The large model APK benefits from IncFS; stream the small test APK normally.
    incremental = apk.name == "app-debug.apk" and Path(str(apk) + ".idsig").is_file()
    mode = ["--incremental"] if incremental else ["--no-incremental", "--no-streaming"]
    subprocess.run([*device, "install", *mode, "-r", "-t", str(apk)], check=True, timeout=1200)

if not arguments.no_ui_automation:
    subprocess.run([*device, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=True, timeout=120)
    subprocess.run([*device, "shell", "wm", "dismiss-keyguard"], check=True, timeout=120)
command = [*device, "shell", "am", "instrument", "-w", "-r"]
if arguments.class_name:
    command += ["-e", "class", arguments.class_name]
if arguments.no_ui_automation:
    command += ["-e", "localUiAutomation", "false"]
command += ["com.sasakiy84.excerptmemo.test/com.sasakiy84.excerptmemo.LocalTestRunner"]
output = []
with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1) as process:
    for line in process.stdout:
        print(line, end="", flush=True)
        output.append(line)
    returncode = process.wait()
result = "".join(output)
if returncode:
    sys.exit(returncode)
summary = re.search(r"OK \((\d+) tests?\)", result)
passed = len(re.findall(r"^INSTRUMENTATION_STATUS_CODE: 0\s*$", result, re.MULTILINE))
if not summary or int(summary.group(1)) != arguments.expected_tests or passed != arguments.expected_tests:
    sys.exit("Android test suite failed, was incomplete, or had an unexpected test count")
if "INSTRUMENTATION_STATUS_CODE: -2" in result or "INSTRUMENTATION_FAILED" in result:
    sys.exit("AndroidJUnitRunner reported a failure")
print(f"Verified {passed} executed Android tests")
