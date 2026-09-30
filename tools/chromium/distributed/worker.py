"""Run one exact compiler action from a hash-verified parity-test snapshot."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import tarfile
import time


def sha(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=pathlib.Path)
    parser.add_argument('sha256')
    parser.add_argument('index', type=int)
    args = parser.parse_args()
    if sha(args.archive) != args.sha256:
        raise RuntimeError('Snapshot digest mismatch')
    root = pathlib.Path('/home/neyron/.cache/upgrid/chromium')
    if not root.exists() or any(root.iterdir()):
        raise RuntimeError('Worker requires an empty, pre-created isolated root')
    with tarfile.open(args.archive, 'r:gz') as bundle:
        for member in bundle.getmembers():
            rel = pathlib.PurePosixPath(member.name)
            if rel.is_absolute() or '..' in rel.parts or not (member.isfile() or member.isdir()):
                raise RuntimeError('Unsafe archive member')
        bundle.extractall(root, filter='data')
    manifest = json.loads((root / 'probe-manifest.json').read_text())
    src = root / 'src'
    if manifest['sourceRoot'] != str(src):
        raise RuntimeError('Compile paths differ from snapshot')
    for item in manifest['inputs']:
        file = src / item['path']
        if not file.resolve().is_relative_to(src) or sha(file) != item['sha256']:
            raise RuntimeError('Input digest mismatch')
        os.utime(file, ns=(item['mtimeNs'], item['mtimeNs']))
    action = manifest['actions'][args.index]
    out = src / 'out/Upgrid'
    out.mkdir(parents=True, exist_ok=True)
    target = out / action['output']
    target.parent.mkdir(parents=True, exist_ok=True)
    # Ninja records the actual command hash and dependencies, enabling comparison
    # with the paused local build. The complete Chromium graph is not executed.
    command = action['command'].replace('$', '$$')
    (out / 'probe.ninja').write_text('rule cxx\n  command = ' + command + '\n'
        '  deps = gcc\n  depfile = ' + action['output'] + '.d\n'
        'build ' + action['output'] + ': cxx\n')
    started = time.monotonic()
    result = subprocess.run([str(src / 'third_party/ninja/ninja'), '-f', 'probe.ninja',
                             '-j', '1', action['output']], cwd=out, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    report = {'worker': args.index, 'snapshotSha256': args.sha256,
              'target': action['output'], 'compileSeconds': time.monotonic() - started,
              'exitCode': result.returncode, 'log': result.stdout[-20000:]}
    if result.returncode == 0:
        command_hash = (out / '.ninja_log').read_text().splitlines()[-1].split('\t')[-1]
        report.update(sha256=sha(target), expectedSha256=action['expectedSha256'],
                      commandHash=command_hash, expectedCommandHash=action['expectedCommandHash'])
        report['identical'] = report['sha256'] == report['expectedSha256']
        report['sameCommand'] = command_hash == action['expectedCommandHash']
    pathlib.Path('result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if result.returncode != 0 or not report['identical'] or not report['sameCommand']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
