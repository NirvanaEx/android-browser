"""Verify a completed shard and optionally import its objects at a stopped build boundary."""
import argparse
import fcntl
import json
import os
import pathlib
import shutil
import shlex
import tarfile
import time
from ninja_cache import read_deps, append_deps
from probe_bundle import SRC, OUT, BASE, sha


def ensure_idle():
    for proc in pathlib.Path('/proc').iterdir():
        try:
            if proc.name.isdigit() and (proc / 'comm').read_text().strip() in ('ninja', 'clang++', 'ld.lld'):
                raise RuntimeError('A compiler/build process is running; refusing cache import')
        except FileNotFoundError:
            pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('artifact', type=pathlib.Path)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--head-sha', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    lock = (OUT / '.upgrid-remote-import.lock').open('a')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    ensure_idle()
    if not (OUT / '.upgrid-build-owner').exists():
        raise RuntimeError('Unowned build output')
    receipt = json.loads((BASE / 'wave1-receipt.json').read_text())
    manifest = json.loads((BASE / 'wave1-manifest.json').read_text())
    with tarfile.open(args.artifact, 'r:gz') as archive:
        report = json.load(archive.extractfile('result.json'))
        if report['snapshotSha256'] != receipt['sha256'] or report['runId'] != args.run_id or report['headSha'] != args.head_sha:
            raise RuntimeError('Artifact does not belong to the expected run/input snapshot')
        shard = report['shard']
        actions = {action['output']: action for action in manifest['shards'][shard]}
        expected = set(actions)
        allowed = {item['path'] for item in report['objects']}
        if len(allowed) != len(report['objects']) or not allowed <= expected:
            raise RuntimeError('Unexpected/duplicate outputs')
        allowed |= {'result.json', '.ninja_log', '.ninja_deps'}
        staging = BASE / 'verified-artifacts' / str(shard)
        staging.mkdir(parents=True, exist_ok=True)
        for member in archive.getmembers():
            if member.name not in allowed or not member.isfile():
                raise RuntimeError('Unexpected artifact member')
        archive.extractall(staging, filter='data')
    _, dependencies = read_deps(staging / '.ninja_deps')
    input_manifest = {item['path']: item for item in manifest['inputs']}
    verified = set()
    for item in report['objects']:
        if sha(staging / item['path']) != item['sha256'] or item['path'] not in dependencies:
            raise RuntimeError('Object or dependency record mismatch')
        if item['logRecord'][3] != item['path']:
            raise RuntimeError('Ninja log output mismatch')
        command = shlex.split(actions[item['path']]['command'])
        direct = [command[0], actions[item['path']]['file'], '../../build/config/warning_suppression.txt',
                  '../../build/config/unsafe_buffers_paths.txt']
        direct += [arg.split('=', 2)[2] for arg in command if arg.startswith('-fmodule-file=')]
        direct += [arg.split('=', 1)[1] for arg in command if arg.startswith('-fmodule-map-file=')]
        for name in (*dependencies[item['path']][1], *direct):
            file = pathlib.Path(os.path.normpath(OUT / name))
            if not file.is_relative_to(SRC):
                raise RuntimeError('Dependency outside checkout')
            rel = file.relative_to(SRC).as_posix()
            if rel in verified:
                continue
            if rel not in input_manifest or sha(file) != input_manifest[rel]['sha256']:
                raise RuntimeError('Compiler input changed or was absent from snapshot: ' + rel)
            verified.add(rel)
    result = {'shard': shard, 'verifiedObjects': len(report['objects']), 'applied': False,
              'snapshotSha256': receipt['sha256'], 'runId': args.run_id, 'headSha': args.head_sha}
    if args.apply:
        ensure_idle()
        if shutil.disk_usage(BASE).free < 20 * 1024**3:
            raise RuntimeError('Preserve 20 GiB free on staging disk')
        backup = BASE / 'before-remote-import'
        backup.mkdir(exist_ok=True)
        for name in ('.ninja_log', '.ninja_deps'):
            if not (backup / name).exists():
                shutil.copy2(OUT / name, backup / name)
        paths, _ = read_deps(OUT / '.ninja_deps', paths_only=True)
        new_deps = {}
        log_lines = []
        old_stats = {}
        for item in report['objects']:
            name = item['path']
            destination = OUT / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            prior = backup / name
            if destination.exists() and not prior.exists():
                prior.parent.mkdir(parents=True, exist_ok=True)
                old_stats[name] = destination.stat().st_mtime_ns
                shutil.copy2(destination, prior)
            temporary = destination.with_name(destination.name + '.upgrid-import')
            shutil.copyfile(staging / name, temporary)
            temporary.replace(destination)
            stamp = destination.stat().st_mtime_ns
            new_deps[name] = (stamp, dependencies[name][1])
            fields = list(item['logRecord'])
            fields[2] = str(stamp)
            log_lines.append('\t'.join(fields) + '\n')
        # Assemble complete logs in sibling files before atomic replacement.
        # If interrupted between replacements, Ninja conservatively rebuilds.
        temp_deps = OUT / '.ninja_deps.upgrid-import'
        shutil.copyfile(OUT / '.ninja_deps', temp_deps)
        append_deps(temp_deps, paths, new_deps)
        read_deps(temp_deps, paths_only=True)
        temp_log = OUT / '.ninja_log.upgrid-import'
        with temp_log.open('wb') as stream:
            stream.write((OUT / '.ninja_log').read_bytes())
            stream.write(''.join(log_lines).encode())
        temp_deps.replace(OUT / '.ninja_deps')
        temp_log.replace(OUT / '.ninja_log')
        result.update(applied=True, importedAtNs=time.time_ns(), originalObjectMtimes=old_stats)
        (backup / f'shard-{shard}-receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
