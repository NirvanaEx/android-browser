"""Cloud-only final Ninja audit and observable progress; never an APK acceptance."""
import collections
import json
import os
import pathlib
import re
import subprocess
import threading
import time

ACTION = re.compile(r'^\[(\d+)/(\d+)\]\s+(\S+)\s+(.+)$')


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def audit_plan(text, imported):
    counts = collections.Counter()
    repeated = []
    for line in text.splitlines():
        match = ACTION.match(line)
        if not match:
            continue
        kind, target = match[3], match[4]
        counts[kind] += 1
        if kind in ('CC', 'CXX') and target in imported:
            repeated.append(target)
    return {'actionsByType': dict(counts), 'totalActions': sum(counts.values()),
            'repeatedImportedObjects': repeated,
            'nativeCompileActions': counts['CC'] + counts['CXX']}


def enforce_plan(report):
    if report['repeatedImportedObjects']:
        raise RuntimeError('Final graph would recompile imported objects; inspect final-plan.json')
    if report['nativeCompileActions'] >= 128:
        raise RuntimeError('Final graph has >=128 undistributed C/C++ actions; inspect final-plan.json')


def prepare(ninja, output, state):
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Final build audit is GitHub-only')
    state = pathlib.Path(state)
    # Called AFTER overlay/GN regeneration, immediately before the real Ninja.
    text = subprocess.check_output([str(ninja), '-C', str(output), '-n',
                                    'chrome_public_apk'], text=True)
    (state / 'final-tasks.log').write_text(text)
    imported = set(json.loads((state / 'imported-outputs.json').read_text()))
    report = audit_plan(text, imported)
    save(state / 'final-plan.json', report)
    print(json.dumps({'stage': 'final-plan', **report}), flush=True)
    enforce_plan(report)
    return Monitor(state, report)


class Monitor:
    def __init__(self, state, plan):
        self.state, self.plan = pathlib.Path(state), plan
        self.lock = threading.Lock()
        self.started = self.last_action = time.monotonic()
        self.completed = 0
        self.total = plan['totalActions']
        self.last = None
        self.check_id = None
        self.last_publish = 0
        self.last_warning = 0

    def line(self, line):
        match = ACTION.match(line.strip())
        if match:
            with self.lock:
                self.completed, self.total = int(match[1]), int(match[2])
                self.last = (match[3] + ' ' + match[4])[:240]
                self.last_action = time.monotonic()

    def request(self, payload, method='POST'):
        endpoint = 'repos/' + os.environ['GITHUB_REPOSITORY'] + '/check-runs'
        if method == 'PATCH':
            endpoint += '/' + str(self.check_id)
        result = subprocess.run(['gh', 'api', '--method', method, endpoint, '--input', '-'],
                                input=json.dumps(payload), text=True, capture_output=True,
                                timeout=15, check=True)
        return json.loads(result.stdout)

    def heartbeat(self, pid, conclusion=None):
        now = time.monotonic()
        with self.lock:
            report = dict(runId=os.environ.get('GITHUB_RUN_ID'), headSha=os.environ.get('GITHUB_SHA'),
                          stage='final-ninja', completedActions=self.completed, totalActions=self.total,
                          lastCompletedAction=self.last, elapsedSeconds=round(now-self.started),
                          secondsSinceCompletedAction=round(now-self.last_action),
                          conclusion=conclusion, apkVerified=False)
        # Process names, cumulative CPU time and RSS only; no argv/environment/secrets.
        try:
            rows = subprocess.check_output(['ps', '-eo', 'pid=,ppid=,comm=,time=,rss='],
                                           text=True, timeout=5).splitlines()
            parsed = [row.split() for row in rows if len(row.split()) == 5]
            descendants = {str(pid)}
            for _ in range(16):
                expanded = descendants | {row[0] for row in parsed if row[1] in descendants}
                if expanded == descendants:
                    break
                descendants = expanded
            report['processes'] = [dict(pid=row[0], name=row[2], cpuTime=row[3], rssKiB=int(row[4]))
                                   for row in parsed if row[0] in descendants]
        except (OSError, subprocess.SubprocessError, ValueError):
            report['processes'] = None
        save(self.state / 'final-progress.json', report)
        with (self.state / 'final-progress.jsonl').open('a') as stream:
            stream.write(json.dumps(report) + '\n')
        print(json.dumps(report), flush=True)
        if report['secondsSinceCompletedAction'] >= 600 and now-self.last_warning >= 300:
            print('::warning::No Ninja action completed for 10+ minutes. Inspect CPU/RSS and final-build.log; this alone does not prove a hang.', flush=True)
            self.last_warning = now
        if conclusion or now-self.last_publish >= 120:
            self.last_publish = now
            payload = {'status': 'completed' if conclusion else 'in_progress', 'output': {
                'title': f"{self.completed}/{self.total} final Ninja actions (not time or APK acceptance)",
                'summary': json.dumps(report)}}
            if conclusion:
                payload['conclusion'] = conclusion
            try:
                if self.check_id is None:
                    payload.update(name='Chromium final Ninja progress', head_sha=os.environ['GITHUB_SHA'])
                    self.check_id = self.request(payload)['id']
                else:
                    self.request(payload, 'PATCH')
            except (OSError, subprocess.SubprocessError, ValueError, KeyError):
                print('Progress check update unavailable; local diagnostics retained.', flush=True)
