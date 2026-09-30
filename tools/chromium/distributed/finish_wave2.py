"""Verify/import the completed remote wave, inspect the graph, and finish the APK."""
import datetime
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tarfile
import import_downloaded
from import_wave import ensure_idle
from probe_bundle import SRC, OUT, BASE

ROOT = pathlib.Path(__file__).resolve().parents[3]
STATE = ROOT / 'build/chromium/features-20260930/distributed-build-state.json'
sys.path.insert(0, str(ROOT / 'tools/chromium'))
from preflight import inspect


def main():
    ensure_idle()
    state = json.loads(STATE.read_text(encoding='utf-8-sig'))
    if (state['runId'] != 36764678861 or state['headSha'] != '1929e127e6758f81ace5cbcd4e868a3a17973294'
            or state['remoteConclusion'] != 'success' or state['snapshotPrefix'] != 'wave2'):
        raise RuntimeError('Unexpected remote wave')
    started = datetime.datetime.now(datetime.timezone.utc)
    state.update(supervisorPid=os.getpid(), supervisorStartedUtc=started.isoformat(),
        supervisorHost='Ubuntu WSL', supervisorCommand='finish_wave2.py',
        supervisorProcessStart=pathlib.Path('/proc/self/stat').read_text().split()[21])
    state.pop('error', None)
    state.pop('failurePhase', None)

    def save(phase, **fields):
        state.update(phase=phase, updatedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), **fields)
        temporary = STATE.with_suffix('.tmp')
        temporary.write_text(json.dumps(state, indent=2) + '\n')
        temporary.replace(STATE)
        print(json.dumps({'phase': phase, **fields}), flush=True)

    try:
        save('wave2-verify-import')
        receipt_path = BASE / 'run-36764678861/download-receipt.json'
        downloaded = json.loads(receipt_path.read_text())
        if (downloaded['runId'] != state['runId'] or downloaded['headSha'] != state['headSha']
                or downloaded['snapshotSha256'] != state['inputSha256']
                or len(downloaded['reports']) != 40 or downloaded['completedObjects'] != 14665
                or any(item['exitCode'] or item['completed'] != item['total'] for item in downloaded['reports'])):
            raise RuntimeError('Incomplete/mismatched download receipt')
        total_bytes = 0
        for report in downloaded['reports']:
            archive = receipt_path.parent / pathlib.PureWindowsPath(report['archive']).name
            with tarfile.open(archive) as bundle:
                total_bytes += sum(item['bytes'] for item in json.load(bundle.extractfile('result.json'))['objects'])
        status = inspect(SRC.parent, min_free_gib=20)
        if not status['storageVerified'] or status['physicalFreeGiB'] * 1024**3 < total_bytes + 21 * 1024**3:
            raise RuntimeError('Insufficient physical WSL disk reserve for imported objects')
        import shutil
        if shutil.disk_usage(BASE).free < 2 * total_bytes + 21 * 1024**3:
            raise RuntimeError('Insufficient staging reserve for extracted objects and backups')
        sys.argv = ['import_downloaded.py', str(receipt_path), '--snapshot-prefix', 'wave2']
        import_downloaded.main()
        save('wave2-imported-graph-check', importedWave2=14665)
        ensure_idle()
        dry_path = BASE / 'wave2-final-dry-run.log'
        with dry_path.open('w') as log:
            subprocess.run([str(SRC / 'third_party/ninja/ninja'), '-C', str(OUT), '-n',
                            'chrome_public_apk'], stdout=log, stderr=subprocess.STDOUT, check=True)
        counts = {}
        for line in dry_path.read_text().splitlines():
            match = re.match(r'^\[\d+/\d+\] (\S+)', line)
            if match:
                counts[match[1]] = counts.get(match[1], 0) + 1
        (BASE / 'wave2-final-dry-run-summary.json').write_text(json.dumps(counts, indent=2) + '\n')
        if counts.get('CXX', 0) > 1000:
            save('wave2-imported-review-needed', remainingActions=counts,
                 reviewReason='More than 1000 CXX actions remain; inspect invalidations before local compilation')
            return
        save('local-final-build', remainingActions=counts, localCompilationRunning=True)
        with (BASE / 'final-build.log').open('w') as log:
            subprocess.run([sys.executable, '-u', str(ROOT / 'tools/chromium/build.py'),
                            '--profile', 'extensions-dev', '--jobs', '6'],
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        receipt = json.loads((SRC.parent / 'upgrid-extensions-dev-build-receipt.json').read_text())
        if datetime.datetime.fromisoformat(receipt['finishedAtUtc']) < started:
            raise RuntimeError('Stale build receipt')
        apk = OUT / 'apks/ChromePublic.apk'
        with apk.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if receipt['sha256'] != digest or pathlib.Path(receipt['apk']) != apk:
            raise RuntimeError('APK does not match fresh receipt')
        save('compiled-awaiting-apk-metadata-and-android-checks', localCompilationRunning=False,
             apk=str(apk), apkBytes=apk.stat().st_size, apkSha256=digest)
    except BaseException as error:
        failed_phase = state['phase']
        save('distributed-finalization-failed', failurePhase=failed_phase, error=str(error),
             localCompilationRunning=False)
        raise


if __name__ == '__main__':
    main()
