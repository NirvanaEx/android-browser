"""Cloud-only storage and provenance helpers; no dependency on the user's PC."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tarfile
import tempfile
from collections import deque
from decimal import Decimal
from concurrent.futures import ThreadPoolExecutor

REPO = 'NirvanaEx/android-browser'
ROOT = pathlib.Path(os.environ.get('UPGRID_CHROMIUM_ROOT', '/home/neyron/.cache/upgrid/chromium'))
STATE = pathlib.Path(os.environ.get('UPGRID_DISTRIBUTED_STATE', '/home/runner/upgrid-state'))
PROJECT = pathlib.Path(__file__).resolve().parents[3]
CHUNK_BYTES = 1800 * 1024**2


def run(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def sha(path):
    with pathlib.Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read(path):
    return json.loads(pathlib.Path(path).read_text())


def write(path, value):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def cloud_only():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REPOSITORY') != REPO:
        raise RuntimeError('This entry point runs only in this repository on GitHub Actions')
    STATE.mkdir(parents=True, exist_ok=True)


def reserve(path, extra=0, gib=20):
    path = pathlib.Path(path)
    while not path.exists():
        path = path.parent
    if shutil.disk_usage(path).free < gib * 1024**3 + extra:
        raise RuntimeError(f'Insufficient free disk at {path}; preserve {gib} GiB')


def tag_checked(tag):
    if not re.fullmatch(r'upgrid-ci-(?:fixture-)?[0-9]+-[0-9]+', tag):
        raise ValueError('Unexpected CI transfer tag')
    return tag


def gh_json(endpoint):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/{endpoint}'], text=True))


def create_transfer(tag):
    tag_checked(tag)
    # Drafts keep unaccepted APKs and complete build caches out of public releases.
    return json.loads(subprocess.check_output([
        'gh', 'api', '--method', 'POST', f'repos/{REPO}/releases',
        '-f', f'tag_name={tag}', '-f', f'target_commitish={os.environ["GITHUB_SHA"]}',
        '-F', 'draft=true', '-f', f'name=Private CI workspace {tag}',
        '-f', 'body=Build inputs and cache; not an accepted application release.'
    ], text=True))


def upload(tag, *paths):
    tag_checked(tag)
    for path in paths:
        # Immutable names: never overwrite another attempt's artifacts.
        run('gh', 'release', 'upload', tag, path, '--repo', REPO)


def download(tag, name, destination, digest=None):
    tag_checked(tag)
    if pathlib.PurePosixPath(name).name != name or '\\' in name:
        raise ValueError('Invalid asset name')
    destination = pathlib.Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    path = destination / name
    if not path.exists():
        run('gh', 'release', 'download', tag, '--repo', REPO, '--pattern', name, '--dir', destination)
    if digest and sha(path) != digest:
        raise RuntimeError('Asset digest mismatch: ' + name)
    return path


def pack_workspace(tag, prefix, checkpoint=None):
    reserve(ROOT)
    destination = STATE / prefix
    destination.mkdir(exist_ok=True)
    # Stream compressed chunks; never keep a second giant monolithic archive.
    process = subprocess.Popen(['tar', '--format=pax', '-I', 'zstd -T2 -3', '-cf', '-', '-C', str(ROOT), '.'], stdout=subprocess.PIPE)
    parts = []
    # One upload overlaps compression of the next chunk. Bound staging to two
    # chunks instead of queuing a second copy of the entire Chromium workspace.
    uploads = ThreadPoolExecutor(max_workers=1)
    pending = deque()
    def send(path):
        upload(tag, path)
        path.unlink()  # Only successfully uploaded temporary chunks.
    try:
        index = 0
        while True:
            if len(pending) >= 2:
                pending.popleft().result()
            first = process.stdout.read(min(1024**2, CHUNK_BYTES))
            if not first:
                break
            reserve(destination, CHUNK_BYTES)
            path = destination / f'{prefix}.{index:04d}.tar.zst.part'
            with path.open('wb') as stream:
                stream.write(first)
                remaining = CHUNK_BYTES - len(first)
                while remaining:
                    block = process.stdout.read(min(1024**2, remaining))
                    if not block:
                        break
                    stream.write(block)
                    remaining -= len(block)
            parts.append({'name': path.name, 'sha256': sha(path), 'bytes': path.stat().st_size})
            pending.append(uploads.submit(send, path))
            print(json.dumps({'stage': 'workspace-packing', 'prefix': prefix,
                              'chunksPacked': len(parts)}), flush=True)
            index += 1
        if process.wait():
            raise RuntimeError('Workspace archiving failed')
        for future in pending:
            future.result()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait()
        process.stdout.close()
        uploads.shutdown(wait=True, cancel_futures=True)
    result = {'schema': 1, 'root': str(ROOT), 'chromiumRevision': read(PROJECT / 'tools/chromium/upstream.json')['commit'],
              'sourceHeadSha': os.environ['GITHUB_SHA'], 'parts': parts,
              'expandedBytes': int(subprocess.check_output(['du', '-sb', str(ROOT)], text=True).split()[0])}
    if checkpoint:
        result['checkpoint'] = checkpoint
    manifest = STATE / f'{prefix}.json'
    write(manifest, result)
    upload(tag, manifest)
    return sha(manifest)


def restore_workspace(tag, prefix, expected_digest=None):
    if ROOT.exists() and any(ROOT.iterdir()):
        raise RuntimeError('Refusing to replace a populated workspace')
    manifest_path = download(tag, prefix + '.json', STATE, expected_digest)
    manifest = read(manifest_path)
    if manifest['root'] != str(ROOT) or manifest['chromiumRevision'] != read(PROJECT / 'tools/chromium/upstream.json')['commit']:
        raise RuntimeError('Incompatible workspace cache')
    reserve(ROOT, extra=manifest['expandedBytes'] + sum(item['bytes'] for item in manifest['parts']))
    ROOT.mkdir(parents=True, exist_ok=True)
    parts = manifest['parts']
    if not parts or len({item['name'] for item in parts}) != len(parts):
        raise RuntimeError('Empty or duplicate workspace parts')
    # Reserve the complete download + expanded tree above, then retrieve up to
    # four independent chunks. map retains manifest order for decompression.
    with ThreadPoolExecutor(max_workers=4) as pool:
        paths = list(pool.map(lambda item: download(tag, item['name'], STATE / prefix,
                                                   item['sha256']), parts))
    # Validate every path/link before extraction, preserving genuine symlinks.
    source = subprocess.Popen(['cat', *map(str, paths)], stdout=subprocess.PIPE)
    process = subprocess.Popen(['zstd', '-dc'], stdin=source.stdout, stdout=subprocess.PIPE)
    source.stdout.close()
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            for index, member in enumerate(archive):
                if index % 5000 == 0:
                    reserve(ROOT)
                archive.extract(member, ROOT, filter='data')
                if member.isfile():
                    stamp = int(Decimal(member.pax_headers.get('mtime', str(member.mtime))) * 10**9)
                    os.utime(ROOT / member.name, ns=(stamp, stamp))
        if process.wait() or source.wait():
            raise RuntimeError('Workspace decompression failed')
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait()
        if source.poll() is None:
            source.terminate()
            source.wait()
        process.stdout.close()
    for path in paths:
        path.unlink()  # Verified downloaded temporary chunks only.
    return manifest


def output(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write(f'{name}={value}\n')
