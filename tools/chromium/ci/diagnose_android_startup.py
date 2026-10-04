"""Bounded cloud-emulator diagnosis of one unchanged APK; never acceptance."""
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import threading
import time
import urllib.request

import android_test as harness
from common import cloud_only

PROFILES = {
    'default': harness.runtime_flags('default'),
    'graphite-off': harness.runtime_flags('graphite-off-diagnostic'),
    'graphite-off-jitless': harness.runtime_flags('graphite-off-diagnostic').rstrip()
                            + ' --js-flags=--jitless\n',
    # Pinned ChildConnectionAllocator.createVariableSize uses service 1
    # (isolatedProcess=true, useAppZygote absent) for SysUtils.isLowEndDevice.
    # This also changes memory policy, so a pass only localizes the start path.
    'graphite-off-low-end': harness.runtime_flags('graphite-off-diagnostic').rstrip()
                            + ' --enable-low-end-device-mode\n',
}
HTML = b'''<!doctype html><title>Upgrid startup HTML</title><p id="ready">HTML ready</p>
<script>document.title='Upgrid startup JS ready';
document.getElementById('ready').textContent='JavaScript ready';</script>'''


def belongs_to_app(text):
    return re.search(r'^Cmdline: ' + re.escape(harness.PACKAGE) + r'(?=[:\s])',
                     text, re.MULTILINE) is not None


def collect_tombstones(directory, seen):
    """Copy text tombstones for this APK only, with a bounded total size."""
    selected = []
    total = 0
    for name in harness.adb('shell', 'ls', '-1', '/data/tombstones').splitlines():
        if not re.fullmatch(r'tombstone_\d+', name):
            continue
        content = harness.command('adb', 'exec-out', 'cat', '/data/tombstones/' + name)
        if len(content) > 8 * 1024**2 or total + len(content) > 64 * 1024**2:
            continue
        if not belongs_to_app(content.decode('utf-8', errors='replace')):
            continue
        digest = hashlib.sha256(content).hexdigest()
        if digest in seen:
            continue
        (directory / (name + '.txt')).write_bytes(content)
        seen.add(digest)
        total += len(content)
        selected.append(dict(name=name, bytes=len(content),
                             sha256=digest))
    return selected


def inspect_page():
    sockets = harness.adb('shell', 'cat', '/proc/net/unix')
    names = re.findall(r'@(chrome_devtools_remote[^\s]*)', sockets)
    if not names:
        return dict(socketReady=False)
    harness.adb('forward', 'tcp:9222', 'localabstract:' + names[0])
    with urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=3) as response:
        pages = json.load(response)
    return dict(socketReady=True, pages=[dict(type=p.get('type'), title=p.get('title'),
                url=p.get('url')) for p in pages],
                javascriptReady=any(p.get('title') == 'Upgrid startup JS ready' for p in pages))


def run_profile(name, flags, base, seen_tombstones):
    directory = base / name
    directory.mkdir()
    harness.EVIDENCE = directory
    harness.adb('shell', 'am', 'force-stop', harness.PACKAGE)
    # These are ephemeral emulator inputs, not application build flags. Keep
    # process isolation, SELinux, seccomp and fullscreen security unchanged.
    flag_file = directory / 'chrome-command-line'
    flag_file.write_text(flags)
    harness.adb('push', flag_file, '/data/local/tmp/chrome-command-line')
    harness.adb('logcat', '-c')
    url = 'http://127.0.0.1:8766/startup-' + name
    result = dict(profile=name, flags=flags.strip(), javascriptReady=False,
                  diagnosticOnly=True, androidAcceptanceVerified=False,
                  distributionApproved=False, attempts=[], diagnosticErrors={})
    harness.adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
                '-d', url, '-p', harness.PACKAGE)
    started = time.monotonic()
    while time.monotonic() - started < 120:
        try:
            if harness.dismiss_notification_prompt():
                harness.adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
                            '-d', url, '-p', harness.PACKAGE)
            observation = inspect_page()
            result['attempts'].append(dict(seconds=round(time.monotonic()-started, 1), **observation))
            if observation.get('javascriptReady'):
                result['javascriptReady'] = True
                break
        except Exception as error:
            result['attempts'].append(dict(seconds=round(time.monotonic()-started, 1), error=repr(error)))
        # The first crash is sufficient for a full tombstone. Wait long enough
        # for debuggerd, rather than relaunching the same failing page repeatedly.
        log = harness.adb('logcat', '-d', timeout=20)
        if time.monotonic()-started > 25 and 'berberis_HandleNoExec' in log:
            result['stoppedAfterRendererCrash'] = True
            break
        time.sleep(2)
    log = harness.adb('logcat', '-d', timeout=20)
    (directory / 'logcat.txt').write_text(log, encoding='utf-8')
    result['crashes'] = harness.app_failures(log)
    for label, operation in (
        ('screenshot', lambda: harness.screenshot('page')),
        ('tombstones', lambda: result.update(tombstones=collect_tombstones(directory, seen_tombstones))),
        ('dropbox', lambda: (directory / 'dropbox.txt').write_text(
            harness.adb('shell', 'dumpsys', 'dropbox', '--print', 'data_app_native_crash', timeout=30))),
    ):
        harness.collect_diagnostic(result['diagnosticErrors'], label, operation)
    result['elapsedSeconds'] = round(time.monotonic()-started, 1)
    (directory / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(dict(profile=name, javascriptReady=result['javascriptReady'],
                          crashes=len(result['crashes']), seconds=result['elapsedSeconds'])), flush=True)
    harness.adb('shell', 'am', 'force-stop', harness.PACKAGE)
    return result


def main():
    cloud_only()
    context = json.loads((harness.EVIDENCE / 'input.json').read_text())
    expected = '2eba4f357c4ff6800662c346e6f218eb5601b556d91c4d4c689132c97b1d26d2'
    if (context['metadata']['sha256'] != expected
            or harness.digest(Path(context['apk'])) != expected):
        raise RuntimeError('This diagnosis requires the unchanged .11 APK')
    if harness.adb('shell', 'getprop', 'ro.hardware').strip() not in ('ranchu', 'goldfish'):
        raise RuntimeError('Full crash collection is only enabled on the disposable cloud emulator')
    harness.adb('root')
    harness.adb('wait-for-device')
    if harness.adb('shell', 'id', '-u').strip() != '0':
        raise RuntimeError('Debuggable emulator required to read full tombstones')
    installed = harness.adb('install', '-r', context['apk'], timeout=300)
    if 'Success' not in installed:
        raise RuntimeError('Candidate installation failed')
    harness.adb('shell', 'am', 'set-debug-app', '--persistent', harness.PACKAGE)
    base = harness.EVIDENCE / 'startup-diagnosis'
    base.mkdir()
    device = {key: harness.adb('shell', 'getprop', key).strip() for key in
              ('ro.build.fingerprint', 'ro.product.cpu.abilist', 'ro.hardware',
               'ro.dalvik.vm.native.bridge', 'ro.build.version.release')}
    device['selinux'] = harness.adb('shell', 'getenforce').strip()
    (base / 'device.json').write_text(json.dumps(device, indent=2)+'\n')

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(HTML)))
            self.end_headers()
            self.wfile.write(HTML)

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 8766), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    harness.adb('reverse', 'tcp:8766', 'tcp:8766')
    results = []
    seen_tombstones = set()
    try:
        # The first three cases were captured by run 37237867123. Follow up on
        # its remaining renderer failure without repeating those experiments.
        for name in ('graphite-off-low-end',):
            print('Android startup diagnosis: ' + name, flush=True)
            results.append(run_profile(name, PROFILES[name], base, seen_tombstones))
    finally:
        server.shutdown()
        (base / 'summary.json').write_text(json.dumps(dict(apk=context['metadata'],
            diagnosticOnly=True, androidAcceptanceVerified=False, distributionApproved=False,
            profiles=results), indent=2)+'\n')
    # A completed experiment is not a passing acceptance. Fail visibly when
    # there is no working configuration, while retaining all comparison data.
    if not any(r['javascriptReady'] and not r['crashes'] for r in results):
        raise SystemExit('No startup profile passed; inspect full tombstones')


if __name__ == '__main__':
    main()
