"""Finish prerequisite generation and dispatch the corrected immutable C++ wave."""
import datetime
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
from status import api, GH

ROOT = pathlib.Path(__file__).resolve().parents[3]
BASE = pathlib.Path('D:/UpgridBuild/distributed-20260930')
STATE = ROOT / 'build/chromium/features-20260930/distributed-build-state.json'
WSL_TOOLS = '/mnt/c/Users/id303/.codex/worktrees/chromium-banana/android-browser/tools/chromium/distributed/'
SRC = '/home/neyron/.cache/upgrid/chromium/src'


def main():
    state = json.loads(STATE.read_text(encoding='utf-8-sig'))
    state.pop('error', None)
    state.pop('failurePhase', None)
    if state['runId'] != 36760005966 or state['headSha'] != '39c90c2f495d10f800db24fb16b4e4cc7f6e49d1':
        raise RuntimeError('Unexpected previous wave')
    if api('/actions/runs/36760005966')['status'] != 'completed':
        raise RuntimeError('Previous remote wave must finish first')
    state.update(supervisorPid=os.getpid(), supervisorStartedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        supervisorCommand='continue_wave2.py', pilotImported=8,
        previousRuns=[{'runId': 36760005966, 'headSha': state['headSha'], 'importedObjects': 1120,
                       'reason': 'Generated native headers were missing'}])

    def save(phase):
        state['phase'] = phase
        state['updatedUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        temp = STATE.with_suffix('.tmp')
        temp.write_text(json.dumps(state, indent=2) + '\n')
        temp.replace(STATE)
        print(json.dumps({'phase': phase}), flush=True)

    def guard():
        for disk in ('C:/', 'D:/'):
            if shutil.disk_usage(disk).free < 20 * 1024**3:
                raise RuntimeError('Preserve 20 GiB free on ' + disk)

    def wsl(phase, arguments):
        guard()
        save(phase)
        log = BASE / (phase + '.log')
        with log.open('w') as stream:
            subprocess.run(['wsl.exe', '-d', 'Ubuntu', '--cd', '/', '--exec', *arguments],
                           stdout=stream, stderr=subprocess.STDOUT, check=True)

    try:
        save('wave2-import-results')
        # The already running importer finishes a shard atomically and writes a
        # final flushed summary. Do not overlap generation with that import.
        deadline = time.monotonic() + 1800
        while True:
            log_path = BASE / 'import-wave1.log'
            with log_path.open('rb') as stream:
                stream.seek(max(0, log_path.stat().st_size - 4096))
                tail = stream.read().decode('utf-8', errors='replace')
            if 'Traceback (most recent call last)' in tail:
                raise RuntimeError('Object import failed; inspect import-wave1.log')
            if '"importedObjects": 1120' in tail:
                break
            if time.monotonic() > deadline:
                raise RuntimeError('Import completion not observed; inspect importer before continuing')
            time.sleep(5)
        imports = [json.loads(path.read_text()) for path in
            (BASE / 'before-remote-import').glob('run-36760005966-shard-*-receipt.json')]
        if len(imports) != 40 or sum(item['verifiedObjects'] for item in imports) != 1120:
            raise RuntimeError('Incomplete import receipts')
        state['importedWave1'] = 1120
        wsl('wave2-generate-inputs', [SRC + '/third_party/ninja/ninja', '-C', SRC + '/out/Upgrid',
            '-f', 'upgrid-generated-inputs.ninja', '-j', '6', 'upgrid_generated_inputs'])
        wsl('wave2-refresh-pending', ['python3', WSL_TOOLS + 'refresh_pending.py'])
        wsl('wave2-prepare-snapshot', ['python3', '-u', WSL_TOOLS + 'prepare_wave.py',
                                    '--prefix', 'wave2', '--pending', 'pending-cxx-wave2.json'])
        receipt = json.loads((BASE / 'wave2-receipt.json').read_text())
        guard()
        save('wave2-upload-snapshot')
        subprocess.run([GH, 'release', 'upload', 'chromium-distributed-inputs-20260930',
            str(BASE / 'wave2-inputs.tar.gz'), '--repo', 'NirvanaEx/android-browser'], check=True)
        workflow = ROOT / '.github/workflows/chromium-distributed-wave.yml'
        text = workflow.read_text()
        if 'wave1-inputs.tar.gz' not in text or state['inputSha256'] not in text:
            raise RuntimeError('Unexpected workflow inputs')
        text = text.replace('wave1-inputs.tar.gz', 'wave2-inputs.tar.gz').replace(state['inputSha256'], receipt['sha256'])
        text = text.replace(' --skip-first ${{ matrix.worker < 2 && 4 || 0 }}', '')
        workflow.write_text(text)
        save('wave2-dispatch')
        files = ['.github/workflows/chromium-distributed-wave.yml',
                 'tools/chromium/distributed/wave_worker.py', 'tools/chromium/distributed/status.py',
                 'tools/chromium/distributed/import_wave.py', 'tools/chromium/distributed/prepare_wave.py',
                 'tools/chromium/distributed/collect_wave.py', 'tools/chromium/distributed/import_downloaded.py',
                 'tools/chromium/distributed/prepare_generators.py', 'tools/chromium/distributed/refresh_pending.py',
                 'tools/chromium/distributed/continue_wave2.py', 'tools/chromium/distributed/README.md']
        result = subprocess.check_output([sys.executable, str(ROOT / 'tools/chromium/distributed/publish_ci.py'),
            'Generate native prerequisites before distributing remaining C++ actions', *files],
            text=True, encoding='utf-8')
        head = json.loads(result)['commit']
        state.update(headSha=head, inputSha256=receipt['sha256'], snapshotPrefix='wave2',
                     totalCxx=receipt['actions'], importedShards=[], remainingDeferredCxx=receipt['deferred'])
        for _ in range(30):
            runs = api('/actions/runs?branch=codex%2Fchromium-distributed&event=push&per_page=20')['workflow_runs']
            matches = [run for run in runs if run['head_sha'] == head and run['name'] == 'Chromium distributed CXX wave']
            if matches:
                state.update(runId=matches[0]['id'], runUrl=matches[0]['html_url'], localCompilationRunning=False)
                save('remote-wave2')
                print(json.dumps({'runId': state['runId'], 'totalCxx': state['totalCxx'], 'url': state['runUrl']}), flush=True)
                return
            time.sleep(2)
        raise RuntimeError('Pushed workflow; run ID not yet visible. Inspect matching head before retrying.')
    except Exception as error:
        state['failurePhase'] = state.get('phase')
        state['error'] = str(error)
        save('wave2-preparation-failed')
        raise


if __name__ == '__main__':
    main()
