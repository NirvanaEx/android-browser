"""Compile a balanced shard; publish progress and preserve completed objects."""
import argparse
import datetime
import json
import os
import pathlib
import re
import signal
import subprocess
import tarfile
import threading
import time
import urllib.request
from worker import sha
from action_paths import object_path


def write_graph(out, actions):
    graph = []
    for index, action in enumerate(actions):
        name = action['output']
        if not object_path(name):
            raise RuntimeError('Invalid object path')
        (out / name).parent.mkdir(parents=True, exist_ok=True)
        graph += [f'rule cxx_{index}', '  command = ' + action['command'].replace('$', '$$'),
                  '  description = CXX ' + name, '  deps = gcc', '  depfile = ' + name + '.d',
                  f'build {name}: cxx_{index}']
    (out / 'wave.ninja').write_text('\n'.join(graph) + '\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=pathlib.Path)
    parser.add_argument('digest')
    parser.add_argument('shard', type=int)
    parser.add_argument('--limit', type=int)
    parser.add_argument('--skip-first', type=int, default=0)
    args = parser.parse_args()
    if sha(args.archive) != args.digest:
        raise RuntimeError('Snapshot checksum mismatch')
    root = pathlib.Path(os.environ.get('UPGRID_CHROMIUM_ROOT', '/home/neyron/.cache/upgrid/chromium'))
    if not root.exists() or any(root.iterdir()):
        raise RuntimeError('Expected empty isolated worker root')
    with tarfile.open(args.archive, 'r:gz') as bundle:
        for item in bundle.getmembers():
            rel = pathlib.PurePosixPath(item.name)
            if rel.is_absolute() or '..' in rel.parts or not (item.isfile() or item.isdir()):
                raise RuntimeError('Unsafe snapshot entry')
    # All entries are verified regular files/directories with relative paths.
    # GNU tar avoids Python's per-file extraction overhead for 200k headers.
    subprocess.run(['tar', '-xzf', str(args.archive.resolve()), '--no-same-owner',
                    '--no-same-permissions', '-C', str(root)], check=True)
    manifest = json.loads((root / 'wave-manifest.json').read_text())
    src, out = root / 'src', root / 'src/out/Upgrid'
    if manifest['sourceRoot'] != str(src) or manifest['outputRoot'] != str(out):
        raise RuntimeError('Compilation paths differ')
    for item in manifest['inputs']:
        file = src / item['path']
        if not file.resolve().is_relative_to(src) or sha(file) != item['sha256']:
            raise RuntimeError('Snapshot input mismatch')
        os.utime(file, ns=(item['mtimeNs'], item['mtimeNs']))
    actions = manifest['shards'][args.shard]
    if args.skip_first < 0 or args.skip_first >= len(actions):
        raise RuntimeError('Invalid number of already accepted actions')
    actions = actions[args.skip_first:]
    if args.limit is not None:
        if args.limit < 1:
            raise RuntimeError('Limit must be positive')
        actions = actions[:args.limit]
    out.mkdir(parents=True, exist_ok=True)
    write_graph(out, actions)
    completed, check_id = 0, None
    token, repo, head = (os.environ.get(name) for name in ('GH_TOKEN', 'GITHUB_REPOSITORY', 'GITHUB_SHA'))

    def api(path, payload, method='POST'):
        request = urllib.request.Request('https://api.github.com/repos/' + repo + path,
            data=json.dumps(payload).encode(), method=method,
            headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                     'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)

    def progress():
        return {'title': f'{completed}/{len(actions)} compiler actions',
                'summary': json.dumps({'shard': args.shard, 'completed': completed, 'total': len(actions),
                    'runId': os.environ.get('GITHUB_RUN_ID'), 'snapshotSha256': args.digest})}

    if token and repo and head:
        try:
            check_id = api('/check-runs', {'name': f'Chromium {manifest.get("wave", "native")} shard {args.shard} progress',
                'head_sha': head, 'status': 'in_progress', 'output': progress()})['id']
        except Exception as error:
            print('Progress check unavailable: ' + type(error).__name__, flush=True)
    stop = threading.Event()

    def monitor():
        while not stop.wait(300):
            if check_id:
                try:
                    api('/check-runs/' + str(check_id), {'output': progress()}, 'PATCH')
                except Exception as error:
                    print('Progress update unavailable: ' + type(error).__name__, flush=True)

    thread = threading.Thread(target=monitor, daemon=True)
    thread.start()
    started = time.monotonic()
    env = {**os.environ, 'NINJA_STATUS': '[%f/%t] '}
    process = subprocess.Popen([str(src / 'third_party/ninja/ninja'), '-f', 'wave.ninja',
        '-j', str(manifest['jobsPerWorker']), '-k', '0'], cwd=out, env=env, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    timer = threading.Timer(300 * 60, lambda: process.poll() is None and os.kill(process.pid, signal.SIGINT))
    timer.daemon = True
    timer.start()
    with pathlib.Path('worker.log').open('w') as log:
        for line in process.stdout:
            log.write(line)
            log.flush()
            print(line, end='', flush=True)
            if match := re.match(r'^\[(\d+)/(\d+)\]', line):
                completed = int(match[1])
    code = process.wait()
    timer.cancel()
    stop.set()
    thread.join(timeout=25)
    records = {}
    if (out / '.ninja_log').exists():
        for line in (out / '.ninja_log').read_text().splitlines():
            fields = line.split('\t')
            if len(fields) == 5:
                records[fields[3]] = fields
    objects = []
    for action in actions:
        file = out / action['output']
        if file.is_file() and action['output'] in records:
            objects.append({'path': action['output'], 'sha256': sha(file),
                            'bytes': file.stat().st_size, 'logRecord': records[action['output']]})
    completed = len(objects)
    report = {'schema': 1, 'shard': args.shard, 'snapshotSha256': args.digest,
              'completed': completed, 'total': len(actions), 'objects': objects,
              'exitCode': code, 'compileSeconds': time.monotonic() - started,
              'headSha': head, 'runId': os.environ.get('GITHUB_RUN_ID'),
              'finishedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    report_path = pathlib.Path('result.json')
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    with tarfile.open('objects.tar.gz', 'w:gz', compresslevel=1) as archive:
        archive.add(report_path, arcname='result.json')
        for item in objects:
            archive.add(out / item['path'], arcname=item['path'])
        for name in ('.ninja_log', '.ninja_deps'):
            if (out / name).exists():
                archive.add(out / name, arcname=name)
    if check_id:
        try:
            api('/check-runs/' + str(check_id), {'status': 'completed',
                'conclusion': 'success' if code == 0 and completed == len(actions) else 'failure',
                'output': progress()}, 'PATCH')
        except Exception as error:
            print('Final progress update unavailable: ' + type(error).__name__, flush=True)
    raise SystemExit(code)


if __name__ == '__main__':
    main()
