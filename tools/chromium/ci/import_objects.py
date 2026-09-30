"""Parallel validation/copy, followed by one coordinated Ninja dependency-log merge."""
import hashlib
import json
import os
import pathlib
import shlex
import shutil
import sys
import tarfile
from concurrent.futures import ThreadPoolExecutor
from common import ROOT, STATE, read, reserve, sha, write
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'distributed'))
from ninja_cache import read_deps, append_deps


def import_objects(archives, manifest, digest, run_id, head, workers=4):
    src, out = ROOT / 'src', ROOT / 'src/out/Upgrid'
    inputs = {item['path']: item for item in manifest['inputs']}
    expected_outputs = {action['output'] for shard in manifest['shards'] for action in shard}
    if any(not name.startswith('obj/') or not name.endswith('.o') or
           '..' in pathlib.PurePosixPath(name).parts or '\\' in name for name in expected_outputs):
        raise RuntimeError('Unsafe output path')
    expanded = 0
    for path in archives:
        with tarfile.open(path) as archive:
            expanded += sum(item.size for item in archive.getmembers())
    reserve(ROOT, extra=expanded * 2)

    def unpack(path):
        with tarfile.open(path) as archive:
            report = json.load(archive.extractfile('result.json'))
            if (str(report['runId']) != str(run_id) or report['headSha'] != head
                    or report['snapshotSha256'] != digest or report['exitCode'] != 0
                    or report['completed'] != report['total']):
                raise RuntimeError('Incomplete or mismatched shard')
            shard = report['shard']
            if type(shard) is not int or not 0 <= shard < len(manifest['shards']):
                raise RuntimeError('Invalid shard index')
            actions = {item['output']: item for item in manifest['shards'][shard]}
            returned = [item['path'] for item in report['objects']]
            if (len(returned) != len(set(returned)) or set(returned) != set(actions)
                    or report['total'] != len(actions)):
                raise RuntimeError('Missing/duplicate/unexpected shard output')
            members = archive.getmembers()
            allowed = set(actions) | {'result.json', '.ninja_log', '.ninja_deps'}
            if len({item.name for item in members}) != len(members):
                raise RuntimeError('Duplicate archive member')
            if any(not item.isfile() or item.name not in allowed for item in members):
                raise RuntimeError('Unsafe object archive')
            destination = STATE / 'unpacked' / str(shard)
            destination.mkdir(parents=True, exist_ok=False)
            archive.extractall(destination, filter='data')
        _, deps = read_deps(destination / '.ninja_deps')
        dependencies = set()
        for item in report['objects']:
            name = item['path']
            if sha(destination / name) != item['sha256'] or name not in deps or item['logRecord'][3] != name:
                raise RuntimeError('Object or dependency record mismatch')
            command = shlex.split(actions[name]['command'])
            direct = [command[0], actions[name]['file'], '../../build/config/warning_suppression.txt',
                      '../../build/config/unsafe_buffers_paths.txt']
            direct += [arg.split('=', 2)[2] for arg in command if arg.startswith('-fmodule-file=')]
            direct += [arg.split('=', 1)[1] for arg in command if arg.startswith('-fmodule-map-file=')]
            for value in (*deps[name][1], *direct):
                file = pathlib.Path(os.path.normpath(out / value))
                if not file.is_relative_to(src):
                    raise RuntimeError('Dependency escaped source root')
                dependencies.add(file.relative_to(src).as_posix())
        return report, deps, destination, dependencies

    reserve(ROOT)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        packages = list(pool.map(unpack, archives))
    outputs = [item['path'] for report, _, _, _ in packages for item in report['objects']]
    if len(outputs) != len(set(outputs)) or set(outputs) != expected_outputs:
        raise RuntimeError('Incomplete or overlapping wave; output unchanged')
    unique_inputs = set().union(*(item[3] for item in packages))

    def verify_input(name):
        if name not in inputs or sha(src / name) != inputs[name]['sha256']:
            raise RuntimeError('Compiler input changed: ' + name)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(verify_input, sorted(unique_inputs)))
    # Only after the entire wave validates do we touch the disposable output.
    copy_jobs = [(item, deps[item['path']], destination) for report, deps, destination, _ in packages
                 for item in report['objects']]

    def copy_object(job):
        item, deps, destination = job
        name = item['path']
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix('.upgrid-import')
        shutil.copyfile(destination / name, temporary)
        temporary.replace(target)
        stamp = target.stat().st_mtime_ns
        record = list(item['logRecord'])
        record[2] = str(stamp)
        return name, (stamp, deps[1]), '\t'.join(record) + '\n'

    reserve(ROOT)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        accepted = list(pool.map(copy_object, copy_jobs))
    paths, _ = read_deps(out / '.ninja_deps', paths_only=True)
    temporary = out / '.ninja_deps.upgrid-import'
    shutil.copyfile(out / '.ninja_deps', temporary)
    append_deps(temporary, paths, {name: deps for name, deps, _ in accepted})
    read_deps(temporary, paths_only=True)
    log = out / '.ninja_log.upgrid-import'
    with log.open('wb') as stream:
        stream.write((out / '.ninja_log').read_bytes())
        stream.write(''.join(line for _, _, line in accepted).encode())
    temporary.replace(out / '.ninja_deps')
    log.replace(out / '.ninja_log')
    result = {'importedObjects': len(accepted), 'uniqueInputsVerified': len(unique_inputs),
              'snapshotSha256': digest, 'runId': str(run_id), 'headSha': head}
    write(STATE / 'import-receipt.json', result)
    return result
