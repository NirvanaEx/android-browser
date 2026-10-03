"""Read APK ZIP metadata without unpacking or modifying the signed APK."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import zipfile


def analyze(path):
    path = Path(path)
    groups = defaultdict(lambda: {'packedBytes': 0, 'unpackedBytes': 0, 'files': 0})
    with zipfile.ZipFile(path) as archive:
        files = [item for item in archive.infolist() if not item.is_dir()]
        for item in files:
            name = item.filename
            group = ('native' if name.startswith('lib/') else
                     'dex' if name.endswith('.dex') else name.split('/')[0])
            groups[group]['packedBytes'] += item.compress_size
            groups[group]['unpackedBytes'] += item.file_size
            groups[group]['files'] += 1
        largest = [{'name': item.filename, 'packedBytes': item.compress_size,
                    'unpackedBytes': item.file_size,
                    'stored': item.compress_type == zipfile.ZIP_STORED}
                   for item in sorted(files, key=lambda item: item.compress_size, reverse=True)[:20]]
        abis = sorted({item.filename.split('/')[1] for item in files
                       if item.filename.startswith('lib/') and item.filename.endswith('.so')})
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'apk': path.name, 'bytes': path.stat().st_size, 'sha256': digest,
            'abis': abis, 'groups': dict(groups), 'largest': largest,
            'zipOverheadBytes': path.stat().st_size - sum(i.compress_size for i in files)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = json.dumps(analyze(args.apk), indent=2) + '\n'
    if args.output:
        args.output.write_text(result, encoding='utf-8')
    else:
        print(result, end='')
