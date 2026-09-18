#!/usr/bin/env python3
"""Collect universal APKs and public signing metadata; never copy keys or credentials."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "artifacts"
DEST.mkdir(exist_ok=True)
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
if not (sdk / "build-tools").is_dir():
    raise SystemExit("Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK")
signer = sdk / "build-tools/36.0.0/apksigner"
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
records = []
for variant in ("debug", "preview"):
    output = ROOT / f"app/build/outputs/apk/{variant}"
    metadata = json.loads((output / "output-metadata.json").read_text())
    universal = [entry for entry in metadata["elements"] if not entry["filters"]]
    if len(universal) != 1:
        raise SystemExit(f"Expected exactly one universal {variant} APK")
    entry = universal[0]
    apk = output / entry["outputFile"]
    if not apk.is_file() or apk.parent.resolve() != output.resolve():
        raise SystemExit(f"Missing or invalid {variant} APK path")
    certificate = subprocess.check_output([str(signer), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    fingerprint = next(line.split(": ", 1)[1] for line in certificate.splitlines()
                       if line.startswith("Signer #1 certificate SHA-256 digest: "))
    shutil.copy2(apk, DEST / apk.name)
    (DEST / f"{variant}-signature.txt").write_text(certificate)
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    records.append({"file": apk.name, "package": metadata["applicationId"],
                    "versionName": entry["versionName"], "versionCode": entry["versionCode"],
                    "sha256": digest,
                    "signerSha256": fingerprint})
(DEST / "build-info.json").write_text(json.dumps({"revision": revision, "uncommittedChanges": dirty, "apks": records}, indent=2) + "\n")
(DEST / "SHA256SUMS").write_text("".join(f'{r["sha256"]}  {r["file"]}\n' for r in records))
shutil.copy2(ROOT / "docs/BUILD_AND_INSTALL.md", DEST / "BUILD_AND_INSTALL.md")
print(f"Collected {len(records)} verified universal APKs in artifacts/")
