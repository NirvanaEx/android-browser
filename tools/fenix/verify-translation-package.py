#!/usr/bin/env python3
"""Verify that the generated Gecko translation changes reached the signed APK."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('checkout', type=Path)
args = parser.parse_args()
result = {'apk_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(), 'files': []}
with zipfile.ZipFile(args.apk) as apk:
    assert apk.testzip() is None, 'Corrupt APK'
    with zipfile.ZipFile(io.BytesIO(apk.read('assets/omni.ja'))) as omni:
        for name in ('translations-engine.worker.js', 'translations-document.sys.mjs'):
            matches = [entry for entry in omni.namelist() if entry.endswith('/translations/' + name)]
            assert len(matches) == 1, f'Missing or ambiguous Gecko resource: {name}'
            packaged = omni.read(matches[0])
            source = (args.checkout / 'toolkit/components/translations/content' / name).read_bytes()
            assert packaged == source, f'Stale translation resource in APK: {name}'
            result['files'].append({'name': matches[0], 'sha256': hashlib.sha256(packaged).hexdigest()})
result['ok'] = True
print(json.dumps(result, indent=2))
