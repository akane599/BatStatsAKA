#!/usr/bin/env python3
"""Install checksum-pinned official Shizuku on an explicitly selected disposable API36 emulator."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

URL = "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"
SHA256 = "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, help="Use a previously downloaded, checksum-verified APK")
    args = parser.parse_args()
    adb_path = shutil.which("adb")
    if not adb_path or not args.serial.startswith("emulator-"):
        parser.error("adb and an explicit emulator-NNNN serial are required")

    def adb(*command):
        return subprocess.check_output([adb_path, "-s", args.serial, *command], text=True, timeout=600).strip()

    if adb("shell", "getprop", "ro.hardware") not in ("ranchu", "goldfish") or adb("shell", "getprop", "ro.build.version.sdk") != "36":
        raise SystemExit("Only a disposable Android16 emulator is supported")
    adb("unroot")
    adb("wait-for-device")
    if adb("shell", "id", "-u") != "2000":
        raise SystemExit("Shizuku must start with shell UID2000; refusing a root-mode test")
    abi = adb("shell", "getprop", "ro.product.cpu.abi")
    if abi not in ("x86_64", "x86", "arm64-v8a", "armeabi-v7a"):
        raise SystemExit("Unsupported emulator ABI")
    with tempfile.TemporaryDirectory(prefix="batstats-shizuku-") as temporary:
        directory = Path(temporary)
        apk = args.apk or directory / "shizuku.apk"
        if not args.apk:
            urllib.request.urlretrieve(URL, apk)
        with apk.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != SHA256:
                raise SystemExit("Shizuku APK checksum mismatch")
        # Official native starter discovers the installed manager with `pm path`.
        # Source: RikkaApps/Shizuku/manager/src/main/jni/starter.cpp.
        starter = directory / "batstats-shizuku-starter"
        with zipfile.ZipFile(apk) as archive:
            starter.write_bytes(archive.read(f"lib/{abi}/libshizuku.so"))
        print(adb("install", "-r", str(apk)))
        adb("shell", "am", "start", "-W", "-n", "moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity")
        adb("push", str(starter), "/data/local/tmp/batstats-shizuku-starter")
        adb("shell", "chmod", "700", "/data/local/tmp/batstats-shizuku-starter")
        print(adb("shell", "/data/local/tmp/batstats-shizuku-starter"))
    print("Started official Shizuku as shell; integration tests still must verify Binder and authorization.")


if __name__ == "__main__":
    main()
