"""Read compact remote-build progress; no workflow mutations."""
import collections
import json
import pathlib
import subprocess
import sys

GH = r'C:\Program Files\GitHub CLI\gh.exe'
REPO = 'repos/NirvanaEx/android-browser'


def api(path):
    return json.loads(subprocess.check_output([GH, 'api', REPO + path], text=True, encoding='utf-8'))


def main():
    state = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
    run = api('/actions/runs/' + str(state['runId']))
    if run['head_sha'] != state['headSha']:
        raise RuntimeError('Unexpected workflow revision')
    jobs = api('/actions/runs/' + str(state['runId']) + '/jobs?per_page=100')['jobs']
    checks = api('/commits/' + state['headSha'] + '/check-runs?per_page=100')['check_runs']
    progress = {}
    for check in checks:
        if not check['name'].startswith('Chromium shard '):
            continue
        item = json.loads(check['output']['summary'])
        if str(item['runId']) == str(state['runId']):
            progress[item['shard']] = item
    print(json.dumps({'runId': run['id'], 'url': run['html_url'], 'status': run['status'],
        'conclusion': run['conclusion'], 'jobs': dict(collections.Counter(job['status'] for job in jobs)),
        'failedJobs': [job['name'] for job in jobs if job['conclusion'] in ('failure', 'timed_out', 'cancelled')],
        'completedCxx': sum(item['completed'] for item in progress.values()),
        'totalCxx': state['totalCxx'], 'workersReporting': len(progress)}, indent=2))


if __name__ == '__main__':
    main()
