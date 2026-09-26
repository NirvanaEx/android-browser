#!/usr/bin/env python3
"""Verify that the generated player assets reached the release APK unchanged."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("checkout", type=Path)
args = parser.parse_args()
assets = "assets/extensions/upgrid_fullscreen/"
generated = args.checkout / "mobile/android/fenix/app/src/main"
result = {"apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(), "files": []}
with zipfile.ZipFile(args.apk) as apk:
    assert apk.testzip() is None, "Corrupt APK"
    for path in sorted((generated / assets).iterdir()):
        if not path.is_file():
            continue
        name = assets + path.name
        packaged = apk.read(name)
        assert packaged == path.read_bytes(), f"Stale player resource: {name}"
        result["files"].append({"name": name, "sha256": hashlib.sha256(packaged).hexdigest()})
    manifest = json.loads(apk.read(assets + "manifest.json"))
    result["extension_version"] = manifest["version"]
    assert b"var automaticFullscreen = true;" in apk.read(assets + "preload.js")
    assert b"var nativeErrorFallback = true;" in apk.read(assets + "player.js")
    assert b"var enginePlayerPreferred = true;" in apk.read(assets + "background.js")
    assert b"var nativePlayerEnabled = true;" in apk.read(assets + "background.js")
result["ok"] = True
print(json.dumps(result, indent=2))
