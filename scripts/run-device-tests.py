#!/usr/bin/env python3
"""Run the same AndroidJUnitRunner suite through adb on slow software emulators."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import shutil
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5556")
parser.add_argument("--expected-tests", type=int, default=15)
parser.add_argument("--reuse-app", action="store_true", help="Reuse the matching debug APK already installed on this device")
parser.add_argument("--no-incremental", action="store_true", help="Use a regular APK install when the emulator's IncFS extraction fails")
parser.add_argument("--root-staging", action="store_true", help="Stage a complete APK on a rooted test emulator; retain Android signature verification")
parser.add_argument("--class-name", help="Select an AndroidJUnit test class")
parser.add_argument("--no-ui-automation", action="store_true", help="For tests that do not access UI automation")
arguments = parser.parse_args()
root = Path(__file__).resolve().parents[1]
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
device = [adb, "-s", arguments.serial]


def install_with_root_staging(apk):
    """Avoid slow FileBridge copies and incomplete IncFS reads on software AVDs."""
    if subprocess.check_output([*device, "shell", "id", "-u"], text=True, timeout=30).strip() != "0":
        sys.exit("--root-staging requires a rooted test emulator")
    remote = "/data/local/tmp/excerpt-test-install.apk"
    subprocess.run([*device, "push", str(apk), remote], check=True, timeout=300)
    created = subprocess.check_output([*device, "shell", "pm", "install-create", "-r", "-t",
                                      "--dexopt-compiler-filter", "verify", "-S", str(apk.stat().st_size)], text=True, timeout=120)
    match = re.search(r"Success: created install session \[(\d+)\]", created)
    if not match:
        sys.exit(created)
    session = match.group(1)
    target = f"/data/app/vmdl{session}.tmp/base.apk"
    committed = False
    try:
        # Opening a short write lets PackageInstaller create its own staging folder.
        subprocess.run([*device, "shell", "pm", "install-write", "-S", "1", session, "base.apk", "-"],
                       input="x", text=True, check=True, timeout=120)
        subprocess.run([*device, "shell", "rm", target], check=True, timeout=30)
        subprocess.run([*device, "shell", "ln", remote, target], check=True, timeout=30)
        subprocess.run([*device, "shell", "chown", "system:system", target], check=True, timeout=30)
        subprocess.run([*device, "shell", "chmod", "644", target], check=True, timeout=30)
        subprocess.run([*device, "shell", "restorecon", target], check=True, timeout=30)
        subprocess.run([*device, "shell", "rm", remote], check=True, timeout=30)
        result = subprocess.run([*device, "shell", "pm", "install-commit", session], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
        print(result.stdout, end="", flush=True)
        result.check_returncode()
        if result.stdout.strip() != "Success":
            sys.exit("Android did not confirm APK installation")
        committed = True
    finally:
        if not committed:
            subprocess.run([*device, "shell", "pm", "install-abandon", session], timeout=30)
        subprocess.run([*device, "shell", "rm", "-f", remote], timeout=30)


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
    root_staging = arguments.root_staging and apk.name == "app-debug.apk"
    incremental = not root_staging and not arguments.no_incremental and apk.name == "app-debug.apk" and Path(str(apk) + ".idsig").is_file()
    if incremental:
        # IncFS may keep serving unread model blocks after installation finishes.
        # Retain an immutable copy so a subsequent Gradle build cannot change them.
        cache = Path(os.environ.get("ANDROID_USER_HOME", "/tmp/excerpt-android-user")) / "test-apks"
        cache.mkdir(parents=True, exist_ok=True)
        stable = cache / (hashlib.sha256(apk.read_bytes()).hexdigest() + ".apk")
        if not stable.is_file():
            shutil.copy2(apk, stable)
        shutil.copy2(Path(str(apk) + ".idsig"), Path(str(stable) + ".idsig"))
        apk = stable
    if root_staging:
        install_with_root_staging(apk)
        continue
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
