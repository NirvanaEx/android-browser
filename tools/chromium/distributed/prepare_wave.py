"""Snapshot pending C++ actions and headers; do not build or modify local output."""
import argparse
import hashlib
import json
import os
import pathlib
import shlex
import shutil
import tarfile
from ninja_cache import read_deps
from probe_bundle import SRC, OUT, BASE, sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prefix', default='wave1')
    parser.add_argument('--pending', default='pending-cxx.json')
    args = parser.parse_args()
    if not args.prefix.isalnum() or pathlib.Path(args.pending).name != args.pending:
        raise RuntimeError('Invalid snapshot name')
    archive = BASE / (args.prefix + '-inputs.tar.gz')
    if archive.exists():
        raise RuntimeError('Preserve existing snapshot')
    if shutil.disk_usage(BASE).free < 25 * 1024**3:
        raise RuntimeError('Insufficient staging space on D')
    pending = json.loads((BASE / args.pending).read_text())
    files = set()
    for name in read_deps(OUT / '.ninja_deps', paths_only=True)[0]:
        p = pathlib.Path(os.path.normpath(OUT / name))
        if p.is_file() and p.suffix not in ('.o', '.a', '.so', '.rlib'):
            files.add(p)
    # Existing deps omit headers behind newly enabled conditionals and module
    # search headers. Include the header trees while excluding caches/test data.
    header_suffixes = {'.h', '.hh', '.hpp', '.hxx', '.inc', '.inl', '.def', '.ipp', '.tcc', '.modulemap'}
    for directory, dirs, names in os.walk(SRC):
        dirs[:] = [name for name in dirs if name not in ('.git', 'out', 'node_modules', '__pycache__')]
        for name in names:
            p = pathlib.Path(directory) / name
            if p.suffix in header_suffixes and p.is_file():
                files.add(p)
    for directory in [OUT / 'gen', OUT / 'obj/build/modules',
                      SRC / 'third_party/llvm-build/Release+Asserts/lib/clang/23/include']:
        for p in directory.rglob('*'):
            if p.is_file() and p.suffix in header_suffixes | {'.pcm', '', '.cc', '.c', '.cpp'}:
                files.add(p)
    files.update(SRC / name for name in ['third_party/ninja/ninja', 'LICENSE',
                 'build/config/warning_suppression.txt', 'build/config/unsafe_buffers_paths.txt'])
    actions, deferred = [], []
    for action in pending:
        argv = shlex.split(action['command'])
        if pathlib.Path(argv[0]).name != 'clang++' or action['directory'] != str(OUT):
            deferred.append(action['output'])
            continue
        inputs = [pathlib.Path(os.path.normpath(OUT / argv[0])),
                  pathlib.Path(os.path.normpath(OUT / action['file']))]
        inputs += [pathlib.Path(os.path.normpath(OUT / arg.split('=', 2)[2]))
                   for arg in argv if arg.startswith('-fmodule-file=')]
        if not all(p.is_file() for p in inputs):
            deferred.append(action['output'])
            continue
        files.update(inputs)
        actions.append(action)
    # Notices accompany distributed header/source snapshots.
    for p in list(files):
        for parent in p.parents:
            if not parent.is_relative_to(SRC):
                break
            for name in ('LICENSE', 'LICENSE.txt', 'LICENSE.TXT', 'COPYING', 'NOTICE', 'README.chromium'):
                if (parent / name).is_file():
                    files.add(parent / name)
    print(json.dumps({'stage': 'hashing', 'actions': len(actions), 'files': len(files),
                      'deferred': len(deferred)}), flush=True)
    inputs = []
    for number, p in enumerate(sorted(files)):
        if p.suffix.lower() in ('.pem', '.key', '.keystore', '.jks', '.p12', '.pfx'):
            raise RuntimeError(f'Key material is not a compiler snapshot input: {p.name}')
        real = p.resolve(strict=True)
        if not real.is_relative_to(SRC) or not real.is_file():
            raise RuntimeError(f'Input outside checkout: {p}')
        info = p.stat()
        inputs.append({'path': p.relative_to(SRC).as_posix(), 'size': info.st_size,
                       'mtimeNs': info.st_mtime_ns, 'sha256': sha(p)})
        if number and number % 25000 == 0:
            print(json.dumps({'stage': 'hashing', 'filesDone': number}), flush=True)
    # Balance shards by previous timings where available; every target appears once.
    costs = {}
    for line in (OUT / '.ninja_log').read_text().splitlines():
        parts = line.split('\t')
        if len(parts) == 5:
            costs[parts[3]] = max(1000, int(parts[1]) - int(parts[0]))
    shards = [[] for _ in range(40)]
    loads = [0] * len(shards)
    for action in sorted(actions, key=lambda a: costs.get(a['output'], 15000), reverse=True):
        index = min(range(len(shards)), key=lambda n: loads[n])
        shards[index].append(action)
        loads[index] += costs.get(action['output'], 15000)
    manifest = {'schema': 1, 'sourceRoot': str(SRC), 'outputRoot': str(OUT),
                'inputs': inputs, 'shards': shards, 'deferred': deferred,
                'totalActions': len(actions), 'mode': 'compile-wave', 'jobsPerWorker': 4}
    metadata = BASE / (args.prefix + '-manifest.json')
    metadata.write_text(json.dumps(manifest) + '\n')
    with tarfile.open(archive, 'w:gz', compresslevel=1, dereference=True) as output:
        output.add(metadata, arcname='wave-manifest.json')
        output.add(BASE / 'llvm-LICENSE.TXT', arcname='LICENSES/llvm-LICENSE.TXT')
        for number, item in enumerate(inputs):
            output.add(SRC / item['path'], arcname='src/' + item['path'], recursive=False)
            if number and number % 25000 == 0:
                print(json.dumps({'stage': 'packing', 'filesDone': number}), flush=True)
    receipt = {'archive': str(archive), 'sha256': sha(archive), 'bytes': archive.stat().st_size,
               'inputFiles': len(inputs), 'inputBytes': sum(item['size'] for item in inputs),
               'actions': len(actions), 'shards': len(shards), 'deferred': len(deferred)}
    (BASE / (args.prefix + '-receipt.json')).write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt), flush=True)


if __name__ == '__main__':
    main()
