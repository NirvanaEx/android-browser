"""Package two previously compiled C++ actions for an isolated remote parity test."""
import hashlib
import json
import os
import pathlib
import shlex
import shutil
import tarfile
import urllib.request

SRC = pathlib.Path(os.environ.get('UPGRID_CHROMIUM_ROOT', '/home/neyron/.cache/upgrid/chromium')) / 'src'
OUT = SRC / 'out/Upgrid'
BASE = pathlib.Path(os.environ.get('UPGRID_DISTRIBUTED_STATE', '/mnt/d/UpgridBuild/distributed-20260930'))
TARGETS = ['obj/third_party/blink/renderer/core/core/frame_console.o',
           'obj/third_party/blink/renderer/core/core/page_scale_constraints_set.o']


def sha(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    if shutil.disk_usage(BASE).free < 20 * 1024**3:
        raise RuntimeError('Preserve 20 GiB free on D')
    database = json.loads((BASE / 'compdb.json').read_text())
    commands = {item['output']: item for item in database if item['output'] in TARGETS}
    if set(commands) != set(TARGETS):
        raise RuntimeError('Missing compiler commands')
    files = set()
    for line in (BASE / 'probe-deps.txt').read_text().splitlines():
        if line.startswith('    '):
            files.add(pathlib.Path(line.strip()))
        elif line and '(VALID)' not in line:
            raise RuntimeError('Cannot package stale dependency information')
    for action in commands.values():
        argv = shlex.split(action['command'])
        if pathlib.Path(argv[0]).name != 'clang++' or action['directory'] != str(OUT):
            raise RuntimeError('Unexpected compiler or directory')
        files.add(pathlib.Path(argv[0]))
        for argument in argv:
            if argument.startswith('-fmodule-file='):
                files.add(pathlib.Path(argument.split('=', 2)[2]))
            elif argument.startswith('-fmodule-map-file='):
                files.add(pathlib.Path(argument.split('=', 1)[1]))
    files.update([pathlib.Path('../../third_party/ninja/ninja'), pathlib.Path('../../LICENSE')])
    files.update([pathlib.Path('../../build/config/warning_suppression.txt'),
                  pathlib.Path('../../build/config/unsafe_buffers_paths.txt')])
    # Clang modules do not enumerate every searchable standard header in .d.
    for directory in ['gen/third_party/libc++/src/include',
                      '../../third_party/libc++abi/src/include',
                      '../../third_party/llvm-build/Release+Asserts/lib/clang/23/include',
                      '../../third_party/android_toolchain/ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include']:
        folder = OUT / directory
        files.update(file.relative_to(OUT) for file in folder.rglob('*') if file.is_file())
    license_file = BASE / 'llvm-LICENSE.TXT'
    if not license_file.exists():
        license_file.write_bytes(urllib.request.urlopen(
            'https://raw.githubusercontent.com/llvm/llvm-project/main/llvm/LICENSE.TXT', timeout=30).read())
    # Retain nearby notices for the distributed source/header files.
    for rel in list(files):
        lexical = pathlib.Path(__import__('os').path.normpath(OUT / rel))
        for parent in lexical.parents:
            if not parent.is_relative_to(SRC):
                break
            for name in ('LICENSE', 'LICENSE.txt', 'LICENSE.TXT', 'COPYING', 'NOTICE', 'README.chromium'):
                if (parent / name).is_file():
                    files.add(pathlib.Path(__import__('os').path.relpath(parent / name, OUT)))
    manifest = []
    for rel in sorted(files):
        lexical = pathlib.Path(__import__('os').path.normpath(OUT / rel))
        resolved = lexical.resolve(strict=True)
        if not resolved.is_relative_to(SRC) or not resolved.is_file():
            raise RuntimeError(f'Input outside source checkout: {rel}')
        archive_name = lexical.relative_to(SRC).as_posix()
        stat = resolved.stat()
        manifest.append({'path': archive_name, 'size': stat.st_size,
                         'mtimeNs': stat.st_mtime_ns, 'sha256': sha(resolved)})
    known_hashes = {}
    for line in (OUT / '.ninja_log').read_text().splitlines():
        parts = line.split('\t')
        if len(parts) == 5 and parts[3] in TARGETS:
            known_hashes[parts[3]] = parts[4]
    actions = [{**commands[target], 'expectedSha256': sha(OUT / target),
                'expectedCommandHash': known_hashes[target]} for target in TARGETS]
    metadata = {'schema': 1, 'sourceRoot': str(SRC), 'outputRoot': str(OUT),
                'actions': actions, 'inputs': manifest, 'mode': 'parity-only'}
    metadata_path = BASE / 'probe-manifest.json'
    metadata_path.write_text(json.dumps(metadata, indent=2) + '\n')
    archive = BASE / 'probe-inputs-v2.tar.gz'
    if archive.exists():
        raise RuntimeError('Bundle already exists; preserve it')
    with tarfile.open(archive, 'w:gz', compresslevel=3, dereference=True) as output:
        output.add(metadata_path, arcname='probe-manifest.json')
        output.add(license_file, arcname='LICENSES/llvm-LICENSE.TXT')
        for item in manifest:
            output.add(SRC / item['path'], arcname='src/' + item['path'], recursive=False)
    receipt = {'archive': str(archive), 'sha256': sha(archive),
               'bytes': archive.stat().st_size, 'inputBytes': sum(item['size'] for item in manifest),
               'inputFiles': len(manifest), 'actions': len(actions)}
    (BASE / 'probe-bundle-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2), flush=True)


if __name__ == '__main__':
    main()
