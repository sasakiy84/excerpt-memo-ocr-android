#!/usr/bin/env python3
"""Check the shipped APK rather than inferring manifest/ABI settings from Gradle."""
import os
import json
import hashlib
from pathlib import Path
import re
import subprocess
import struct
import sys
import zipfile

apk, version = sys.argv[1:]
assert Path(apk).stat().st_size <= 800_000_000, "APK exceeds the 800 MB budget"
aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0/aapt"
metadata = subprocess.check_output([str(aapt), "dump", "badging", apk], text=True)
assert "name='com.sasakiy84.excerptmemo'" in metadata
assert f"versionName='{version}'" in metadata
assert "sdkVersion:'36'" in metadata
assert "targetSdkVersion:'36'" in metadata
permissions = subprocess.check_output([str(aapt), "dump", "permissions", apk], text=True)
assert "android.permission.INTERNET" not in permissions
assert "android.permission.CAMERA" in permissions
with zipfile.ZipFile(apk) as archive:
    models = [name for name in archive.namelist() if name.endswith(".onnx")]
    manifest = json.loads(archive.read("assets/models-manifest.json"))
    files = dict(manifest["files"])
    for source in manifest.get("additional_sources", []):
        files.update(source["files"])
    expected_models = {"assets/" + name for name in files if name.endswith(".onnx")}
    assert set(models) == expected_models
    for name, digest in files.items():
        assert hashlib.sha256(archive.read("assets/" + name)).hexdigest() == digest, name
    total_bytes = sum(archive.getinfo(name).file_size for name in models)
    assert total_bytes <= 800_000_000, "Model bundle exceeds the 800 MB budget"
    for name in models:
        assert archive.getinfo(name).compress_type == zipfile.ZIP_STORED
    assert any(name.startswith("lib/arm64-v8a/") and name.endswith("libonnxruntime.so") for name in archive.namelist())
    assert not any(name.startswith("lib/x86_64/") for name in archive.namelist())
    for name in archive.namelist():
        if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
            elf = archive.read(name)
            assert elf[:6] == b"\x7fELF\x02\x01", name
            offset = struct.unpack_from("<Q", elf, 32)[0]
            entry_size, count = struct.unpack_from("<HH", elf, 54)
            for index in range(count):
                header = offset + index * entry_size
                if struct.unpack_from("<I", elf, header)[0] == 1:
                    assert struct.unpack_from("<Q", elf, header + 48)[0] >= 16384, f"16 KB page alignment: {name}"
print(f"Verified {version}: Android 16+, camera, offline models, arm64 ONNX Runtime")
