#!/usr/bin/env python3
"""Read GitHub job/shard counters without relying on a running local build."""
import argparse
import datetime
import json
import pathlib
import shutil
import subprocess

GH = shutil.which('gh') or r'C:\Program Files\GitHub CLI\gh.exe'
REPO = 'NirvanaEx/android-browser'


def api(path):
    return json.loads(subprocess.check_output([GH, 'api', f'repos/{REPO}/{path}'], text=True, encoding='utf-8'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('run', type=int)
    parser.add_argument('--state', type=pathlib.Path)
    args = parser.parse_args()
    run = api(f'actions/runs/{args.run}')
    if args.state:
        state = json.loads(args.state.read_text())
        if state['runId'] != args.run or state['headSha'] != run['head_sha']:
            raise RuntimeError('Run identity does not match saved state')
    jobs = api(f'actions/runs/{args.run}/jobs?per_page=100')['jobs']
    active = [job for job in jobs if job['status'] == 'in_progress']
    shards = [job for job in jobs if job['name'].startswith('native (')]
    counters = {}
    for check in api(f"commits/{run['head_sha']}/check-runs?per_page=100")['check_runs']:
        if not check['name'].startswith('Chromium shard '):
            continue
        try:
            count = json.loads(check['output']['summary'])
        except (TypeError, ValueError):
            continue
        if str(count.get('runId')) != str(args.run):
            continue
        shard = count['shard']
        if shard not in counters or check['id'] > counters[shard]['checkId']:
            counters[shard] = {**count, 'checkId': check['id']}
    phase = 'queued'
    if any(job['name'] == 'validate' for job in active):
        phase = 'validate'
    if any(job['name'] == 'prepare' for job in active):
        phase = 'prepare'
    if any(job['name'].startswith('native (') for job in active):
        phase = 'native'
    if any(job['name'] == 'apk' for job in active):
        phase = 'apk'
    if run['status'] == 'completed':
        phase = 'compiled-awaiting-verification' if run['conclusion'] == 'success' else 'stopped'
    result = {'runId': args.run, 'headSha': run['head_sha'], 'phase': phase,
              'runStatus': run['status'], 'conclusion': run['conclusion'], 'url': run['html_url'],
              'observedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'activeRunners': sum(bool(job.get('runner_id')) for job in active),
              'completedNativeJobs': sum(job['conclusion'] == 'success' for job in shards),
              'nativeJobsCreated': len(shards), 'shardsReporting': len(counters),
              'nativeCompletedReported': sum(item['completed'] for item in counters.values()),
              'nativeTotalReported': sum(item['total'] for item in counters.values()),
              'nativeCounterComplete': bool(shards) and len(counters) == len(shards),
              'activeSteps': [{'job': job['name'], 'step': step['name']} for job in active
                              for step in job['steps'] if step['status'] == 'in_progress'],
              'failedJobs': [job['name'] for job in jobs if job['conclusion'] in ('failure', 'timed_out', 'cancelled')]}
    if args.state:
        path = args.state.with_name('cloud-monitoring-progress.json')
        if path.exists():
            previous = json.loads(path.read_text())
            if previous.get('runId') == args.run and previous.get('phase') == phase:
                result['previousSamePhase'] = {key: previous.get(key) for key in
                    ('observedUtc', 'nativeCompletedReported', 'nativeTotalReported', 'completedNativeJobs')}
        path.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
