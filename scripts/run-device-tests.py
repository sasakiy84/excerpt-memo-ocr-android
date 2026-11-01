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
    subprocess.run([*device, "install", "--no-streaming", "-r", "-t", str(apk)], check=True, timeout=1200)

command = [*device, "shell", "am", "instrument", "-w", "-r",
           "com.sasakiy84.excerptmemo.test/androidx.test.runner.AndroidJUnitRunner"]
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
