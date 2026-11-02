#!/usr/bin/env python3
"""Build the matching official OpenCV JNI for Linux host tests, without OpenPNP."""
import hashlib
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

COMMIT = "49486f61fb25722cbcf586b7f4320921d46fb38e"
SOURCE_SHA256 = "8f00b42869ab2836be36090f6631ae4c38ba59171e22a69a8e0f92f8ef1771d4"
CMAKE_SHA256 = "5a1133ff103c71eb5120e2cc3de922733e7d8a26a98ae716397e8676adb367bf"
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "host-opencv" / "4.12.0"
cache.mkdir(parents=True, exist_ok=True)

def fetch(url, name, sha):
    path = cache / name
    if not path.exists():
        with urllib.request.urlopen(url, timeout=120) as source, path.open("wb") as target:
            import shutil
            shutil.copyfileobj(source, target)
    if hashlib.file_digest(path.open("rb"), "sha256").hexdigest() != sha:
        raise SystemExit("Checksum mismatch: " + name)
    return path

source = fetch("https://codeload.github.com/opencv/opencv/tar.gz/" + COMMIT, "opencv.tar.gz", SOURCE_SHA256)
cmake = fetch("https://github.com/Kitware/CMake/releases/download/v3.31.6/cmake-3.31.6-linux-x86_64.tar.gz", "cmake.tar.gz", CMAKE_SHA256)
for archive, directory in [(source, "opencv-" + COMMIT), (cmake, "cmake-3.31.6-linux-x86_64")]:
    if not (cache / directory).exists():
        with tarfile.open(archive) as package:
            package.extractall(cache, filter="data")
binary = str(cache / "cmake-3.31.6-linux-x86_64/bin/cmake")
build = cache / "build"
subprocess.run([binary, "-S", str(cache / ("opencv-" + COMMIT)), "-B", str(build),
    "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_LIST=core,imgproc,java", "-DBUILD_SHARED_LIBS=OFF",
    "-DBUILD_JAVA=ON", "-DOPENCV_JAVA_SDK_BUILD_TYPE=JAVA", "-DBUILD_TESTS=OFF",
    "-DBUILD_PERF_TESTS=OFF", "-DBUILD_EXAMPLES=OFF", "-DBUILD_opencv_python2=OFF",
    "-DBUILD_opencv_python3=OFF", "-DWITH_IPP=OFF", "-DWITH_ITT=OFF", "-DWITH_OPENCL=OFF",
    "-DWITH_FFMPEG=OFF", "-DWITH_OPENEXR=OFF", "-DWITH_PROTOBUF=OFF"], check=True)
subprocess.run([binary, "--build", str(build), "--parallel", "3", "--target", "opencv_java"], check=True)
print("Host OpenCV:", build / "lib/libopencv_java4120.so")
