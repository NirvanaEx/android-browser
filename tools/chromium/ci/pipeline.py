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
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from common import (ROOT, STATE, PROJECT, REPO, cloud_only, create_transfer, download, gh_json,
                    output, pack_workspace, read, reserve, restore_workspace, run, sha, upload, write)

TOOLS = PROJECT / 'tools/chromium'
SRC, OUT = ROOT / 'src', ROOT / 'src/out/Upgrid'
sys.path.insert(0, str(TOOLS / 'distributed'))
from action_paths import object_path, host_object


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


def pending_native(wave='native'):
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
             if match[1] in ('cc', 'cxx') or match[1].endswith(('_cc', '_cxx'))]
    with database.open('w') as stream:
        ninja('-t', 'compdb', *rules, stdout=stream)
    actions = [item for item in read(database) if item['command'] in commands
               and object_path(item['output'])
               and host_object(item['output']) == (wave == 'host')
               and item['command'].split(' ', 1)[0].endswith(('/clang++', '/clang'))]
    write(STATE / 'pending-cxx.json', actions)
    return actions


def host_inputs(args):
    from host_wave import write_inputs
    actions = pending_native('host')
    wrapper, candidates, inputs = write_inputs(ninja, actions, OUT, STATE)
    dry = STATE / 'host-input-tasks.log'
    with dry.open('w') as stream:
        ninja('-f', wrapper.name, '-n', 'upgrid_host_inputs', stdout=stream)
    report = dict(hostCandidates=candidates, directInputs=inputs,
                  bootstrapActions=sum(bool(re.match(r'^\[\d+/\d+\]', line)) for line in dry.read_text().splitlines()))
    write(STATE / 'host-plan-audit.json', report)
    print(json.dumps(report), flush=True)
    if not args.audit_only:
        ninja('-f', wrapper.name, '-j', '4', 'upgrid_host_inputs')


def select_cached_workspace(releases, revision):
    # Prefer completed outputs. If none exist, the immutable prepared workspace
    # of a failed build still saves fetching the entire Chromium checkout.
    # It is only a source seed: GN/Ninja regenerate and validate every output.
    for prefix, required in [('cache', {'cache.json', 'apk-verification.json'}),
                             ('workspace', {'workspace.json'}),
                             ('host-workspace', {'host-workspace.json', 'host-plan.json'})]:
        for release in releases:
            candidate = release['tag_name']
            if not re.fullmatch(r'upgrid-ci-[0-9]+-[0-9]+', candidate):
                continue
            if not required <= {asset['name'] for asset in release['assets']}:
                continue
            cache = read(download(candidate, prefix + '.json', STATE / 'cache-candidates' / candidate))
            if (prefix == 'workspace' and 'plan.json' not in {asset['name'] for asset in release['assets']}
                    and cache.get('checkpoint') != 'native-prerequisites-v1'):
                continue
            if cache['root'] == str(ROOT) and cache['chromiumRevision'] == revision:
                return candidate, prefix
    return '', 'cache'


def transfer_tag():
    return f"upgrid-ci-{os.environ['GITHUB_RUN_ID']}-{os.environ['GITHUB_RUN_ATTEMPT']}"


def find_transfer(tag):
    probe = STATE / 'transfer-access.json'
    if probe.exists():
        saved = read(probe)
        if saved.get('tag') == tag and isinstance(saved.get('releaseId'), int):
            return gh_json('releases/' + str(saved['releaseId']))
    # GET releases/tags/{tag} excludes drafts. List authenticated releases and
    # select the exact tag instead; never interpret a draft's 404 as absence.
    found = []
    page = 1
    while True:
        releases = gh_json(f'releases?per_page=100&page={page}')
        found.extend(item for item in releases if item.get('tag_name') == tag)
        if len(releases) < 100:
            break
        page += 1
    if len(found) > 1:
        raise RuntimeError('Ambiguous duplicate CI transfer drafts')
    return found[0] if found else None


def verify_transfer(tag, release=None):
    release = release if release is not None else find_transfer(tag)
    if release is None:
        raise RuntimeError('CI transfer draft is missing')
    if (release.get('draft') is not True or release.get('tag_name') != tag
            or release.get('target_commitish') != os.environ['GITHUB_SHA']):
        raise RuntimeError('CI transfer must be a private draft for this exact workflow commit')
    return release


def preflight(args):
    tag = transfer_tag()
    # A trusted dispatcher may reserve the draft with workflow scope. The job
    # token still verifies its exact identity and proves its own upload access.
    release = find_transfer(tag)
    if release is None:
        # Use the create response directly: the release listing can lag behind
        # creation, even when the exact draft is already readable by numeric ID.
        release = create_transfer(tag)
    verify_transfer(tag, release)
    probe = STATE / 'transfer-access.json'
    write(probe, dict(runId=os.environ['GITHUB_RUN_ID'], headSha=os.environ['GITHUB_SHA'],
                      tag=tag, releaseId=release['id'], purpose='Verify draft upload access before heavy build work'))
    upload(tag, probe)
    print(json.dumps(dict(stage='transfer-access-verified', tag=tag)), flush=True)


def prepare_sources(args):
    tag = transfer_tag()
    verify_transfer(tag)  # Fail before downloading or compiling any build inputs.
    config = configure()
    cache_tag = args.cache_tag
    cache_prefix = 'cache'
    if cache_tag == 'auto':
        revision = read(TOOLS / 'upstream.json')['commit']
        cache_tag, cache_prefix = select_cached_workspace(gh_json('releases?per_page=100'), revision)
    print(json.dumps({'stage': 'prepare', 'restoredCache': cache_tag or None,
                      'cacheKind': cache_prefix}), flush=True)
    if cache_tag:
        restore_workspace(cache_tag, cache_prefix)
        configure()
    else:
        run(sys.executable, TOOLS / 'prepare.py', '--checkout', ROOT)
    # System dependencies are installed only on the disposable CI VM.
    run('sudo', 'bash', SRC / 'build/install-build-deps.sh', '--android', '--no-prompt')
    run(sys.executable, TOOLS / 'prepare.py', '--checkout', ROOT, '--hooks')
    run(sys.executable, TOOLS / 'build.py', '--checkout', ROOT, '--profile', config['profile'], '--generate-only')


def prepare_native_snapshot(args):
    tag = args.tag or transfer_tag()
    prefix = args.wave
    sys.path.insert(0, str(TOOLS / 'distributed'))
    import prepare_wave
    # Licensing file is pinned to the checked out compiler's accompanying notice.
    notice = SRC / 'third_party/llvm-build/Release+Asserts/LICENSE.TXT'
    if not notice.exists():
        notice = SRC / 'third_party/llvm-libc/src/LICENSE.TXT'
    if not notice.exists():
        raise RuntimeError('LLVM license file missing; do not publish compiler without its notice')
    shutil.copyfile(notice, STATE / 'llvm-LICENSE.TXT')
    sys.argv = ['prepare_wave.py', '--prefix', prefix, '--shards', str(args.shards)]
    prepare_wave.main()
    receipt = read(STATE / f'{prefix}-receipt.json')
    if receipt['deferred']:
        raise RuntimeError('Missing native source/module prerequisites; refusing speculative wave')
    upload(tag, STATE / f'{prefix}-inputs.tar.gz', STATE / f'{prefix}-manifest.json', STATE / f'{prefix}-receipt.json')
    return receipt


def prepare_snapshots(args):
    tag = args.tag or transfer_tag()
    verify_transfer(tag)
    config = configure()
    wave = args.wave
    workspace_prefix = 'host-workspace' if wave == 'host' else 'workspace'
    plan_name = 'host-plan.json' if wave == 'host' else 'plan.json'
    actions = pending_native(wave)
    # Ninja has finished all writes before these readers start. The workspace
    # and compiler snapshot are independent immutable views of the same tree.
    # Even if native packaging fails, finish the checkpoint upload for reuse.
    with ThreadPoolExecutor(max_workers=2) as pool:
        workspace = pool.submit(pack_workspace, tag, workspace_prefix,
                                checkpoint=f'{wave}-prerequisites-v1')
        snapshot = pool.submit(prepare_native_snapshot, args)
        workspace_digest = workspace.result()
        receipt = snapshot.result()
    manifest = read(STATE / f'{wave}-manifest.json')
    shards = [i for i, items in enumerate(manifest['shards']) if items]
    plan = {'schema': 1, 'runId': os.environ['GITHUB_RUN_ID'], 'headSha': os.environ['GITHUB_SHA'],
            'tag': tag, 'snapshotSha256': receipt['sha256'], 'workspaceSha256': workspace_digest,
            'manifestSha256': sha(STATE / f'{wave}-manifest.json'), 'actions': len(actions),
            'wave': wave, 'workspacePrefix': workspace_prefix,
            'snapshotName': f'{wave}-inputs.tar.gz', 'manifestName': f'{wave}-manifest.json',
            'shards': shards, 'release': config,
            'chromiumRevision': read(TOOLS / 'upstream.json')['commit']}
    write(STATE / plan_name, plan)
    upload(tag, STATE / plan_name)
    output('tag', tag)
    output('plan_sha256', sha(STATE / plan_name))
    output('matrix', json.dumps({'worker': shards or [0]}))
    output('actions', len(actions))


def prepare(args):
    prepare_sources(args)
    native_prerequisites()
    prepare_snapshots(args)


def first_wave(args):
    # A warm incremental build must not pay for an empty host wave and a second
    # full workspace transfer. Continue on this VM directly when tools are ready.
    args.wave = 'host' if pending_native('host') else 'native'
    if args.wave == 'native':
        native_prerequisites()
    prepare_snapshots(args)
    output('wave', args.wave)


def get_plan(args):
    name = 'host-plan.json' if args.wave == 'host' else 'plan.json'
    plan = read(download(args.tag, name, STATE, args.plan_sha256))
    if plan['headSha'] != os.environ['GITHUB_SHA'] or str(plan['runId']) != os.environ['GITHUB_RUN_ID']:
        raise RuntimeError('Plan belongs to a different workflow revision/run')
    if plan.get('wave', 'native') != args.wave:
        raise RuntimeError('Compiler wave mismatch')
    return plan


def worker(args):
    plan = get_plan(args)
    archive = download(args.tag, plan.get('snapshotName', 'native-inputs.tar.gz'), STATE, plan['snapshotSha256'])
    if args.worker not in plan['shards']:
        raise RuntimeError('Unexpected worker index')
    result = subprocess.run([sys.executable, str(TOOLS / 'distributed/wave_worker.py'),
                             str(archive), plan['snapshotSha256'], str(args.worker)], cwd=STATE)
    archive = STATE / 'objects.tar.gz'
    if archive.exists():
        prefix = 'host-objects' if args.wave == 'host' else 'objects'
        target = STATE / f"{prefix}-{plan['runId']}-{os.environ['GITHUB_RUN_ATTEMPT']}-{args.worker}.tar.gz"
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


def restore_wave(args):
    plan = get_plan(args)
    restore_workspace(args.tag, plan.get('workspacePrefix', 'workspace'), plan['workspaceSha256'])
    configure()
    run('sudo', 'bash', SRC / 'build/install-build-deps.sh', '--android', '--no-prompt')
    manifest = read(download(args.tag, plan.get('manifestName', 'native-manifest.json'), STATE, plan['manifestSha256']))
    releases = gh_json('releases?per_page=100')
    release = next(item for item in releases if item['tag_name'] == args.tag)
    available = {}
    for item in release['assets']:
        prefix = 'host-objects' if args.wave == 'host' else 'objects'
        match = re.fullmatch(prefix + '-' + str(plan['runId']) + r'-(\d+)-(\d+)\.tar\.gz', item['name'])
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
    write(STATE / f'restored-{args.wave}.json', dict(runId=plan['runId'], headSha=plan['headSha'],
          workspaceSha256=plan['workspaceSha256'], wave=args.wave))
    return plan


def finalize(args):
    restore_wave(args)
    assemble(args)
    save_cache(args)


def assemble(args):
    plan = get_plan(args)
    restored = read(STATE / 'restored-native.json')
    if restored != dict(runId=plan['runId'], headSha=plan['headSha'],
                        workspaceSha256=plan['workspaceSha256'], wave='native'):
        raise RuntimeError('Assembly requires the verified native workspace and objects')
    with (STATE / 'remaining-tasks.log').open('w') as log:
        ninja('-n', 'chrome_public_apk', stdout=log)
    started = datetime.datetime.now(datetime.timezone.utc)
    run(sys.executable, TOOLS / 'build.py', '--checkout', ROOT, '--profile', plan['release']['profile'], '--jobs', '4')
    build_receipt = read(ROOT / f"upgrid-{plan['release']['profile']}-build-receipt.json")
    if datetime.datetime.fromisoformat(build_receipt['finishedAtUtc']) < started:
        raise RuntimeError('Stale build receipt')
    verification = verify_apk(plan['release'])
    verification['buildTag'] = args.tag
    if verification['sha256'] != build_receipt['sha256']:
        raise RuntimeError('APK digest differs from build receipt')
    write(STATE / 'apk-verification.json', verification)
    from apk_size import analyze
    write(STATE / 'apk-size.json', analyze(OUT / 'apks/ChromePublic.apk'))
    upload(args.tag, OUT / 'apks/ChromePublic.apk', STATE / 'apk-verification.json', STATE / 'apk-size.json', ROOT / f"upgrid-{plan['release']['profile']}-build-receipt.json")
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f"APK compiled and verified: {verification['versionName']} ({verification['bytes']} bytes).\n\n"
                      f"Private APK tag: `{args.tag}`. Android acceptance and distribution remain pending.\n")


def start_android(args):
    verification = read(STATE / 'apk-verification.json')
    if (verification['headSha'] != os.environ['GITHUB_SHA']
            or str(verification['runId']) != os.environ['GITHUB_RUN_ID']
            or verification.get('buildTag') != args.tag):
        raise RuntimeError('Cannot dispatch Android tests for an unrelated APK')
    result = subprocess.check_output(['gh', 'workflow', 'run', 'chromium-full.yml', '--repo', REPO,
              '--ref', os.environ['GITHUB_REF_NAME'], '-f', 'mode=android-test', '-f', f'build_tag={args.tag}'], text=True)
    write(STATE / 'android-dispatch.json', dict(buildTag=args.tag, apkSha256=verification['sha256'],
          requested=True, acceptanceVerified=False, response=result.strip()))
    print(result, flush=True)


def save_cache(args):
    verification = read(STATE / 'apk-verification.json')
    if verification['sha256'] != sha(OUT / 'apks/ChromePublic.apk'):
        raise RuntimeError('Candidate changed before cache checkpoint')
    pack_workspace(args.tag, 'cache')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=['preflight', 'prepare', 'sources', 'prerequisites',
                                         'host-inputs', 'first-wave', 'restore-wave', 'snapshots', 'worker', 'finalize',
                                         'assemble', 'start-android', 'save-cache'])
    parser.add_argument('--wave', choices=['host', 'native'], default='native')
    parser.add_argument('--audit-only', action='store_true')
    parser.add_argument('--cache-tag', default='auto')
    parser.add_argument('--shards', type=int, default=40, choices=range(1, 41))
    parser.add_argument('--tag')
    parser.add_argument('--plan-sha256')
    parser.add_argument('--worker', type=int)
    args = parser.parse_args()
    cloud_only()
    os.environ['UPGRID_CHROMIUM_ROOT'] = str(ROOT)
    os.environ['UPGRID_DISTRIBUTED_STATE'] = str(STATE)
    started = time.monotonic()
    status = 'failed'
    try:
        {'preflight': preflight, 'prepare': prepare, 'sources': prepare_sources,
         'host-inputs': host_inputs, 'restore-wave': restore_wave,
         'first-wave': first_wave,
         'assemble': assemble, 'start-android': start_android, 'save-cache': save_cache,
         'prerequisites': lambda _: native_prerequisites(), 'snapshots': prepare_snapshots,
         'worker': worker, 'finalize': finalize}[args.stage](args)
        status = 'success'
    finally:
        timing = dict(stage=args.stage, status=status, seconds=round(time.monotonic() - started, 1),
                      runId=os.environ['GITHUB_RUN_ID'], headSha=os.environ['GITHUB_SHA'])
        write(STATE / f'timing-{args.stage}.json', timing)
        print(json.dumps(timing), flush=True)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write(f"{args.stage}: {status}, {timing['seconds']} seconds.\n\n")


if __name__ == '__main__':
    main()
