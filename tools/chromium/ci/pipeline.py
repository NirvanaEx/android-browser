#!/usr/bin/env python3
"""Complete GitHub-only Chromium workflow: bootstrap, shard, assemble, verify, cache."""
import argparse
import datetime
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile
from concurrent.futures import ThreadPoolExecutor
from common import (ROOT, STATE, PROJECT, REPO, cloud_only, create_transfer, download, gh_json,
                    output, pack_workspace, read, reserve, restore_workspace, run, sha, upload, write)

TOOLS = PROJECT / 'tools/chromium'
SRC, OUT = ROOT / 'src', ROOT / 'src/out/Upgrid'


def configure():
    config = read(TOOLS / 'ci/release.json')
    templates = {'extensions-dev': 'args-extensions-dev.gn',
                 'extensions-ci': 'args-extensions.gn'}
    if config.get('profile') not in templates:
        raise RuntimeError('Unsupported CI build profile')
    template = TOOLS / templates[config['profile']]
    text = template.read_text()
    for key, value in [('android_override_version_name', config['versionName']),
                       ('android_override_version_code', str(config['versionCode']))]:
        text, count = re.subn(r'^' + key + r' = "[^"]+"$', key + ' = ' + json.dumps(value), text, flags=re.MULTILINE)
        if count != 1:
            raise RuntimeError('Unexpected version configuration')
    if template.read_text() != text:
        template.write_text(text)
    if (OUT / 'args.gn').exists():
        # Explicit CI version/config migration in an owned disposable workspace.
        if not (OUT / '.upgrid-build-owner').exists():
            raise RuntimeError('Unowned output directory')
        if (OUT / 'args.gn').read_text() != text:
            (OUT / 'args.gn').write_text(text)
    return config


def ninja(*args, stdout=None):
    return run(SRC / 'third_party/ninja/ninja', '-C', OUT, *args, stdout=stdout)


def native_prerequisites():
    # Select only nodes reachable from this APK, including Clang module PCMs.
    graph = STATE / 'apk-graph.dot'
    with graph.open('w') as stream:
        ninja('-t', 'graph', 'chrome_public_apk', stdout=stream)
    targets = set()
    with graph.open() as stream:
        for line in stream:
            match = re.match(r'"[^"\n]+" \[label="([^"\n]+)"', line)
            if not match:
                continue
            name = match[1]
            if (name.startswith('gen/') and name.endswith(('.h', '.hh', '.hpp', '.inc', '.cc', '.cpp', '.c', '.modulemap'))
                    or name.endswith('.pcm')):
                if any(char in name for char in ' $:\n') or '..' in name.split('/'):
                    raise RuntimeError('Unexpected native prerequisite syntax')
                targets.add(name)
    if not targets:
        raise RuntimeError('No native prerequisites found')
    graph.unlink()
    wrapper = OUT / 'upgrid-ci-prerequisites.ninja'
    wrapper.write_text('include build.ninja\nbuild upgrid_ci_prerequisites: phony ' + ' '.join(sorted(targets)) + '\n')
    ninja('-f', wrapper.name, '-j', '4', 'upgrid_ci_prerequisites')


def pending_native():
    plan = STATE / 'pending.txt'
    with plan.open('w') as stream:
        ninja('-n', '-v', 'chrome_public_apk', stdout=stream)
    commands = {re.sub(r'^\[\d+/\d+\] ', '', line.rstrip('\n')) for line in plan.open()
                if re.match(r'^\[\d+/\d+\] ', line)}
    database = STATE / 'compdb.json'
    # C++ rules only: unrelated Rust metadata may contain escaped raw bytes.
    rule_files = [OUT / 'toolchain.ninja', *OUT.glob('*/toolchain.ninja')]
    rules = [match[1] for file in rule_files for match in
             re.finditer(r'^rule (\S+)$', file.read_text(), re.MULTILINE)
             if match[1] == 'cxx' or match[1].endswith('_cxx')]
    with database.open('w') as stream:
        ninja('-t', 'compdb', *rules, stdout=stream)
    actions = [item for item in read(database) if item['command'] in commands
               and item['output'].startswith('obj/') and item['output'].endswith('.o')
               and item['command'].split(' ', 1)[0].endswith('/clang++')]
    write(STATE / 'pending-cxx.json', actions)
    return actions


def prepare(args):
    tag = f"upgrid-ci-{os.environ['GITHUB_RUN_ID']}-{os.environ['GITHUB_RUN_ATTEMPT']}"
    config = configure()
    cache_tag = args.cache_tag
    if cache_tag == 'auto':
        cache_tag = ''
        revision = read(TOOLS / 'upstream.json')['commit']
        for release in gh_json('releases?per_page=100'):
            candidate = release['tag_name']
            if not re.fullmatch(r'upgrid-ci-[0-9]+-[0-9]+', candidate):
                continue
            if not {'cache.json', 'apk-verification.json'} <= {asset['name'] for asset in release['assets']}:
                continue
            cache = read(download(candidate, 'cache.json', STATE / 'cache-candidates' / candidate))
            if cache['root'] == str(ROOT) and cache['chromiumRevision'] == revision:
                cache_tag = candidate
                break
    print(json.dumps({'stage': 'prepare', 'restoredCache': cache_tag or None}), flush=True)
    if cache_tag:
        restore_workspace(cache_tag, 'cache')
        configure()
    else:
        run(sys.executable, TOOLS / 'prepare.py', '--checkout', ROOT)
    # System dependencies are installed only on the disposable CI VM.
    run('sudo', 'bash', SRC / 'build/install-build-deps.sh', '--android', '--no-prompt')
    run(sys.executable, TOOLS / 'prepare.py', '--checkout', ROOT, '--hooks')
    run(sys.executable, TOOLS / 'build.py', '--checkout', ROOT, '--profile', config['profile'], '--generate-only')
    native_prerequisites()
    actions = pending_native()
    sys.path.insert(0, str(TOOLS / 'distributed'))
    import prepare_wave
    # Licensing file is pinned to the checked out compiler's accompanying notice.
    notice = SRC / 'third_party/llvm-build/Release+Asserts/LICENSE.TXT'
    if not notice.exists():
        notice = SRC / 'third_party/llvm-libc/src/LICENSE.TXT'
    if not notice.exists():
        raise RuntimeError('LLVM license file missing; do not publish compiler without its notice')
    shutil.copyfile(notice, STATE / 'llvm-LICENSE.TXT')
    sys.argv = ['prepare_wave.py', '--prefix', 'native', '--shards', str(args.shards)]
    prepare_wave.main()
    receipt = read(STATE / 'native-receipt.json')
    if receipt['deferred']:
        raise RuntimeError('Missing native source/module prerequisites; refusing speculative wave')
    create_transfer(tag)
    upload(tag, STATE / 'native-inputs.tar.gz', STATE / 'native-manifest.json', STATE / 'native-receipt.json')
    workspace_digest = pack_workspace(tag, 'workspace')
    manifest = read(STATE / 'native-manifest.json')
    shards = [i for i, items in enumerate(manifest['shards']) if items]
    plan = {'schema': 1, 'runId': os.environ['GITHUB_RUN_ID'], 'headSha': os.environ['GITHUB_SHA'],
            'tag': tag, 'snapshotSha256': receipt['sha256'], 'workspaceSha256': workspace_digest,
            'manifestSha256': sha(STATE / 'native-manifest.json'), 'actions': len(actions),
            'shards': shards, 'release': config,
            'chromiumRevision': read(TOOLS / 'upstream.json')['commit']}
    write(STATE / 'plan.json', plan)
    upload(tag, STATE / 'plan.json')
    output('tag', tag)
    output('plan_sha256', sha(STATE / 'plan.json'))
    output('matrix', json.dumps({'worker': shards or [0]}))
    output('actions', len(actions))


def get_plan(args):
    plan = read(download(args.tag, 'plan.json', STATE, args.plan_sha256))
    if plan['headSha'] != os.environ['GITHUB_SHA'] or str(plan['runId']) != os.environ['GITHUB_RUN_ID']:
        raise RuntimeError('Plan belongs to a different workflow revision/run')
    return plan


def worker(args):
    plan = get_plan(args)
    archive = download(args.tag, 'native-inputs.tar.gz', STATE, plan['snapshotSha256'])
    if args.worker not in plan['shards']:
        raise RuntimeError('Unexpected worker index')
    result = subprocess.run([sys.executable, str(TOOLS / 'distributed/wave_worker.py'),
                             str(archive), plan['snapshotSha256'], str(args.worker)], cwd=STATE)
    archive = STATE / 'objects.tar.gz'
    if archive.exists():
        target = STATE / f"objects-{plan['runId']}-{os.environ['GITHUB_RUN_ATTEMPT']}-{args.worker}.tar.gz"
        archive.rename(target)
        upload(args.tag, target)
    if result.returncode:
        raise RuntimeError(f'Native shard {args.worker} failed; completed objects retained')


def verify_apk(config):
    apk = OUT / 'apks/ChromePublic.apk'
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None or 'AndroidManifest.xml' not in archive.namelist():
            raise RuntimeError('Corrupt APK')
        abis = sorted({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')})
    sdk = SRC / 'third_party/android_sdk/public/build-tools'
    aapt = sorted(sdk.glob('*/aapt'))[-1]
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or (package[1], package[2], package[3]) != (config['package'], str(config['versionCode']), config['versionName']):
        raise RuntimeError('APK metadata mismatch')
    if abis != [config['abi']]:
        raise RuntimeError('Unexpected APK ABI')
    signer = aapt.parent / 'apksigner'
    signing = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})', signing)
    if not certificate or certificate[1].lower() != config['signerSha256']:
        raise RuntimeError('APK signing identity differs from the configured update identity')
    return {**config, 'sha256': sha(apk), 'bytes': apk.stat().st_size,
            'runId': os.environ['GITHUB_RUN_ID'], 'headSha': os.environ['GITHUB_SHA'],
            'chromiumRevision': read(TOOLS / 'upstream.json')['commit'],
            'verifiedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'androidAcceptanceVerified': False, 'distributionApproved': False}


def finalize(args):
    plan = get_plan(args)
    restore_workspace(args.tag, 'workspace', plan['workspaceSha256'])
    configure()
    run('sudo', 'bash', SRC / 'build/install-build-deps.sh', '--android', '--no-prompt')
    manifest = read(download(args.tag, 'native-manifest.json', STATE, plan['manifestSha256']))
    releases = gh_json('releases?per_page=100')
    release = next(item for item in releases if item['tag_name'] == args.tag)
    available = {}
    for item in release['assets']:
        match = re.fullmatch(r'objects-' + str(plan['runId']) + r'-(\d+)-(\d+)\.tar\.gz', item['name'])
        if match and int(match[1]) <= int(os.environ['GITHUB_RUN_ATTEMPT']):
            shard, attempt = int(match[2]), int(match[1])
            if shard not in available or attempt > available[shard][0]:
                available[shard] = (attempt, item['name'])
    if not set(plan['shards']) <= available.keys():
        raise RuntimeError('Missing returned shards')
    with ThreadPoolExecutor(max_workers=4) as pool:
        archives = list(pool.map(lambda shard: download(args.tag, available[shard][1], STATE / 'objects'), plan['shards']))
    if archives:
        from import_objects import import_objects
        imported = import_objects(archives, manifest, plan['snapshotSha256'], plan['runId'], plan['headSha'])
        print(json.dumps(imported), flush=True)
    with (STATE / 'remaining-tasks.log').open('w') as log:
        ninja('-n', 'chrome_public_apk', stdout=log)
    started = datetime.datetime.now(datetime.timezone.utc)
    run(sys.executable, TOOLS / 'build.py', '--checkout', ROOT, '--profile', plan['release']['profile'], '--jobs', '4')
    build_receipt = read(ROOT / f"upgrid-{plan['release']['profile']}-build-receipt.json")
    if datetime.datetime.fromisoformat(build_receipt['finishedAtUtc']) < started:
        raise RuntimeError('Stale build receipt')
    verification = verify_apk(plan['release'])
    if verification['sha256'] != build_receipt['sha256']:
        raise RuntimeError('APK digest differs from build receipt')
    write(STATE / 'apk-verification.json', verification)
    from apk_size import analyze
    write(STATE / 'apk-size.json', analyze(OUT / 'apks/ChromePublic.apk'))
    upload(args.tag, OUT / 'apks/ChromePublic.apk', STATE / 'apk-verification.json', STATE / 'apk-size.json', ROOT / f"upgrid-{plan['release']['profile']}-build-receipt.json")
    # Save a complete reusable state, so the next run rebuilds only changed inputs.
    pack_workspace(args.tag, 'cache')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f"APK compiled and verified: {verification['versionName']} ({verification['bytes']} bytes).\n\n"
                      f"Reusable cache tag: `{args.tag}`. Android acceptance and distribution remain pending.\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=['prepare', 'worker', 'finalize'])
    parser.add_argument('--cache-tag', default='auto')
    parser.add_argument('--shards', type=int, default=40, choices=range(1, 41))
    parser.add_argument('--tag')
    parser.add_argument('--plan-sha256')
    parser.add_argument('--worker', type=int)
    args = parser.parse_args()
    cloud_only()
    os.environ['UPGRID_CHROMIUM_ROOT'] = str(ROOT)
    os.environ['UPGRID_DISTRIBUTED_STATE'] = str(STATE)
    {'prepare': prepare, 'worker': worker, 'finalize': finalize}[args.stage](args)


if __name__ == '__main__':
    main()
