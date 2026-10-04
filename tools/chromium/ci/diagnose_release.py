"""Read the completed .9 cache on GitHub; no compilation or APK execution."""
import json
import pathlib
import re
import shutil
import subprocess
import tarfile
from concurrent.futures import ThreadPoolExecutor
from common import cloud_only, STATE, download, gh_json, read, reserve, sha, write

TAG = 'upgrid-ci-37156601112-1'
HEAD = '15ee93fd7dcc2900c1f8f0542a168113499b059a'
BUILD_ID = '849f83441c984f6f'
ADDRESSES = ['a29a544', 'a29a0d4', 'a2998e4', 'a283368', 'a282c18',
             'a380188', 'a376aec', 'd582434', 'd55f67c', 'd55f828',
             '42ef058', '4a4cc10', '42cf184', 'a316420', 'a3461ec',
             'a3458e8', 'a3bb17c', 'a3bb028', 'a3ba8d8']


def selected(name):
    path = pathlib.PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts:
        return False
    name = str(path)
    if name == 'src/out/Upgrid/.ninja_log':
        return True
    return bool(re.fullmatch(r'src/out/Upgrid/(?:lib\.unstripped/)?libchrome(?:_combined)?\.so', name))


def extract(stream, destination):
    files = []
    with tarfile.open(fileobj=stream, mode='r|') as archive:
        for member in archive:
            if not selected(member.name):
                continue
            if not member.isfile() or member.size > 2 * 1024**3:
                raise RuntimeError('Invalid diagnostic member')
            path = destination / pathlib.PurePosixPath(member.name)
            if path.exists():
                raise RuntimeError('Duplicate diagnostic member')
            path.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, path.open('wb') as output:
                shutil.copyfileobj(source, output)
            files.append(path)
            print(json.dumps({'extracted': member.name, 'bytes': member.size}), flush=True)
    return files


def main():
    cloud_only()
    destination = STATE / 'diagnosis'
    reports = destination / 'reports'
    reports.mkdir(parents=True, exist_ok=True)
    release = gh_json('releases/402705976')
    if (release['tag_name'] != TAG or release['target_commitish'] != HEAD
            or not release['draft']):
        raise RuntimeError('Unexpected release identity')
    metadata = next(a for a in release['assets'] if a['name'] == 'cache.json')
    digest = metadata.get('digest', '').removeprefix('sha256:')
    if not re.fullmatch('[a-f0-9]{64}', digest):
        raise RuntimeError('Missing cache manifest digest')
    manifest = read(download(TAG, 'cache.json', destination, digest))
    if manifest['sourceHeadSha'] != HEAD:
        raise RuntimeError('Wrong cache head')
    parts = manifest['parts']
    if not parts or len({p['name'] for p in parts}) != len(parts):
        raise RuntimeError('Invalid cache parts')
    reserve(destination, extra=sum(p['bytes'] for p in parts) + 5 * 1024**3, gib=5)
    with ThreadPoolExecutor(max_workers=4) as pool:
        paths = list(pool.map(lambda p: download(TAG, p['name'], destination, p['sha256']), parts))
    source = subprocess.Popen(['cat', *map(str, paths)], stdout=subprocess.PIPE)
    decoder = subprocess.Popen(['zstd', '-dc'], stdin=source.stdout, stdout=subprocess.PIPE)
    source.stdout.close()
    try:
        files = extract(decoder.stdout, destination / 'selected')
        # Consume trailing padding so a successful tar end cannot mask a
        # truncated/corrupt zstd frame or leave its writer blocked.
        while decoder.stdout.read(1024**2):
            pass
        if decoder.wait() or source.wait():
            raise RuntimeError('Cache decompression failed')
    finally:
        for process in (decoder, source):
            if process.poll() is None:
                process.terminate()
            process.wait()
        decoder.stdout.close()
    result = dict(sourceRun=37156601112, sourceHead=HEAD, cacheManifestSha=digest,
                  expectedBuildId=BUILD_ID, addresses=ADDRESSES, files=[], matchedSymbols=False)
    for path in files:
        if path.name == '.ninja_log':
            shutil.copyfile(path, reports / 'ninja-log.tsv')
            continue
        notes = subprocess.check_output(['readelf', '-n', str(path)], text=True)
        match = re.search(r'Build ID: ([a-f0-9]+)', notes)
        build_id = match[1] if match else None
        result['files'].append(dict(path=str(path.relative_to(destination)),
                                   sha256=sha(path), buildId=build_id))
        if build_id == BUILD_ID:
            symbols = subprocess.check_output(['llvm-symbolizer-18', '--demangle',
                '--obj=' + str(path), *['0x' + a for a in ADDRESSES]], text=True)
            label = 'unstripped' if 'lib.unstripped' in path.parts else 'stripped'
            (reports / (label + '-' + path.name + '-stack.txt')).write_text(symbols)
            result['matchedSymbols'] |= any(line and line != '??' and not line.startswith('??:')
                                           for line in symbols.splitlines())
    write(reports / 'identity.json', result)
    if not (reports / 'ninja-log.tsv').exists():
        raise RuntimeError('No final Ninja log found')


if __name__ == '__main__':
    main()
