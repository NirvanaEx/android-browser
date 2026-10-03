"""Cloud Android smoke/regression evidence for the exact signed ARM64 APK.

This is not a physical-device performance/DRM acceptance certificate.
"""
import argparse
import hashlib
import http.server
import io
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import traceback
import urllib.request
import xml.etree.ElementTree as ET

REPO = 'NirvanaEx/android-browser'
PACKAGE = 'com.upgrid.chromium'
BASELINE = 'upgrid-android-baseline-768003111'
BASELINE_SHA = '058403f2646440fb2507101beaeba87d8a8470c523561d231b9df494ac942fd2'
WORK = Path('android-test-inputs')
EVIDENCE = Path('android-evidence')


def command(*args, **kwargs):
    kwargs.setdefault('timeout', 180)
    return subprocess.check_output(list(map(str, args)), **kwargs)


def adb(*args, timeout=45):
    return command('adb', *args, timeout=timeout).decode('utf-8', errors='replace')


def collect_diagnostic(errors, name, operation):
    try:
        operation()
    except Exception as error:
        errors[name] = repr(error)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(name, value):
    (EVIDENCE / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def fetch(tag):
    WORK.mkdir(exist_ok=True)
    EVIDENCE.mkdir(exist_ok=True)
    baseline_dir = WORK / 'baseline'
    command('gh', 'release', 'download', BASELINE, '--repo', REPO, '--pattern', '*.apk', '--dir', baseline_dir, timeout=900)
    baseline = next(baseline_dir.glob('*.apk'))
    assert digest(baseline) == BASELINE_SHA, 'Baseline APK digest mismatch'
    if tag == 'baseline':
        apk = baseline
        metadata = dict(sha256=BASELINE_SHA, versionCode=768003111, baseline=True)
    else:
        if not re.fullmatch(r'upgrid-ci-[0-9]+-[0-9]+', tag):
            raise ValueError('Unexpected private build tag')
        candidate = WORK / 'candidate'
        command('gh', 'release', 'download', tag, '--repo', REPO, '--pattern', 'ChromePublic.apk', '--pattern', 'apk-verification.json', '--dir', candidate, timeout=900)
        apk = candidate / 'ChromePublic.apk'
        metadata = json.loads((candidate / 'apk-verification.json').read_text())
        assert metadata['package'] == PACKAGE
        assert digest(apk) == metadata['sha256'], 'Candidate APK digest mismatch'
        metadata['baseline'] = False
    save('input.json', dict(tag=tag, apk=str(apk.resolve()), baselineApk=str(baseline.resolve()), metadata=metadata))


PAGE = '''<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:8px;background:#eef;font:16px sans-serif}video{width:100%;height:240px;background:black}
button{padding:18px}#root:fullscreen{background:#152333}.site{color:red;background:white}
body.clipped #wrap{transform:translate(30px,15px);overflow:hidden;clip-path:inset(8px);width:70%;height:190px}</style>
<div id="root"><div id="wrap"><video id="video" src="/sample.mp4" muted loop playsinline></video>
<p class="site">WEBSITE CONTROLS MUST NOT MIX WITH UPGRID</p></div>
<button id="play">Play</button><button id="container">Container fullscreen</button>
<button id="direct">Video fullscreen</button><pre id="status"></pre>
<script>
const v=document.querySelector('video'); window.v=v;
window.probe={frames:0,loads:0,pauses:0};
v.addEventListener('loadstart',()=>probe.loads++);v.addEventListener('pause',()=>probe.pauses++);
function frame(){probe.frames++;v.requestVideoFrameCallback(frame)}v.requestVideoFrameCallback(frame);
document.querySelector('#play').onclick=()=>v.play();
document.querySelector('#container').onclick=()=>document.querySelector('#root').requestFullscreen();
document.querySelector('#direct').onclick=()=>v.requestFullscreen();
window.state=()=>({...probe,time:v.currentTime,paused:v.paused,videoFullscreen:v.matches(':fullscreen'),
 fullscreen:!!document.fullscreenElement,width:v.videoWidth,height:v.videoHeight,
 source:v.currentSrc,storage:localStorage.getItem('upgrid-ci')});
if(location.pathname==='/clipped')document.body.className='clipped';
if(location.pathname==='/shadow'){
 const host=document.createElement('div');document.querySelector('#wrap').append(host);
 const s=host.attachShadow({mode:'closed'});s.innerHTML='<style>video{width:100%;height:240px}</style><p>SHADOW SITE UI</p>';s.append(v);
}
if(location.pathname==='/iframe'){
 document.body.innerHTML='<iframe style="width:100%;height:550px" allowfullscreen src="http://localhost:8766/direct"></iframe>';
}
</script>'''


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WORK), **kwargs)

    def do_GET(self):
        if self.path.split('?')[0] in ('/direct', '/clipped', '/shadow', '/iframe'):
            data = PAGE.encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            super().do_GET()

    def log_message(self, *_):
        pass


class CDP:
    def __init__(self):
        import websocket
        pages = json.load(urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=3))
        save('devtools-targets.json', pages)
        page = next(p for p in pages if p.get('type') == 'page' and ':8766/' in p.get('url', ''))
        self.ws = websocket.create_connection(page['webSocketDebuggerUrl'], timeout=15, suppress_origin=True)
        self.serial = 0

    def call(self, method, params=None):
        self.serial += 1
        self.ws.send(json.dumps(dict(id=self.serial, method=method, params=params or {})))
        while True:
            reply = json.loads(self.ws.recv())
            if reply.get('id') == self.serial:
                if 'error' in reply:
                    raise RuntimeError(reply['error'])
                return reply.get('result', {})

    def js(self, expression):
        result = self.call('Runtime.evaluate', dict(expression=expression, returnByValue=True, awaitPromise=True))
        if 'exceptionDetails' in result:
            raise RuntimeError(result['exceptionDetails'])
        return result.get('result', {}).get('value')


def wait_for(fn, timeout=30):
    end = time.monotonic() + timeout
    error = None
    while time.monotonic() < end:
        try:
            value = fn()
            if isinstance(value, ET.Element) or value:
                return value
        except Exception as exc:
            error = exc
        time.sleep(1)
    raise AssertionError(f'Timed out: {error or fn}')


def ui():
    # uiautomator can exit successfully with a null root. Never reuse an old dump.
    adb('shell', 'rm', '-f', '/sdcard/upgrid-ui.xml')
    adb('shell', 'uiautomator', 'dump', '/sdcard/upgrid-ui.xml', timeout=20)
    return ET.fromstring(adb('shell', 'cat', '/sdcard/upgrid-ui.xml'))


def find(label):
    return next((n for n in ui().iter('node') if label in
                 (n.get('text'), n.get('content-desc'))), None)


def tap(label):
    node = wait_for(lambda: find(label))
    tap_node(node)


def tap_node(node):
    bounds = list(map(int, re.findall(r'\d+', node.get('bounds'))))
    assert len(bounds) == 4
    adb('shell', 'input', 'tap', (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)


def dismiss_notification_prompt():
    tree = ui()
    # Match the browser-owned rationale, not an arbitrary site's "No thanks".
    nodes = [n for n in tree.iter('node') if n.get('package') == PACKAGE]
    if not any(n.get('resource-id') == PACKAGE + ':id/notification_permission_rationale_title' for n in nodes):
        return False
    decline = next(n for n in nodes if n.get('resource-id') == PACKAGE + ':id/negative_button')
    screenshot('notification-rationale')
    tap_node(decline)
    print('Android: declined the first-run notification rationale', flush=True)
    return True


def screenshot(name):
    data = command('adb', 'exec-out', 'screencap', '-p', timeout=20)
    (EVIDENCE / (name + '.png')).write_bytes(data)
    return data


def open_page(path='/direct'):
    url = 'http://127.0.0.1:8766' + path
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d',
        url, '-p', PACKAGE)
    return connect_page(initial_url=url)


def launch_saved_tab():
    resolved = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief',
                   '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', PACKAGE)
    component = next(line.strip() for line in resolved.splitlines()
                     if re.fullmatch(r'[\w.]+/[\w.]+', line.strip()))
    adb('shell', 'am', 'start', '-W', '-n', component)
    return connect_page()


def connect_page(initial_url=None):
    # DevTools is browser-owned; no desktop browser is launched.
    started = time.monotonic()
    attempts = []
    def discover():
        try:
            if dismiss_notification_prompt() and initial_url:
                # Only initial navigation is retried, never saved-tab restoration.
                adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
                    '-d', initial_url, '-p', PACKAGE)
            sockets = adb('shell', 'cat', '/proc/net/unix')
            names = re.findall(r'@(chrome_devtools_remote[^\s]*)', sockets)
            save('devtools-sockets.json', names)
            if not names:
                raise RuntimeError('Browser DevTools socket is not ready')
            adb('forward', 'tcp:9222', 'localabstract:' + names[0])
            return CDP()
        except Exception as error:
            attempts.append(dict(elapsedSeconds=round(time.monotonic() - started, 1), error=repr(error)))
            save('startup-attempts.json', attempts)
            print('Android: waiting for browser: ' + repr(error), flush=True)
            raise
    # First ARM64 launch includes translation on a fresh x86 emulator. API 35
    # evidence shows its native UI can appear after the former 90-second limit.
    cdp = wait_for(discover, timeout=300 if initial_url else 180)
    wait_for(lambda: cdp.js('typeof state === "function"'))
    return cdp


def run():
    from PIL import Image, ImageChops, ImageStat
    context = json.loads((EVIDENCE / 'input.json').read_text())
    checks = {}
    diagnostic_errors = {}
    save('device.json', dict(model=adb('shell', 'getprop', 'ro.product.model').strip(),
                            abis=adb('shell', 'getprop', 'ro.product.cpu.abilist').strip(),
                            android=adb('shell', 'getprop', 'ro.build.version.release').strip(),
                            physical=False, nativeTranslation=True))
    command('ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi',
            '-i', 'testsrc2=size=640x360:rate=15', '-t', '24', '-c:v', 'libx264',
            '-pix_fmt', 'yuv420p', '-movflags', '+faststart', WORK / 'sample.mp4')
    server = http.server.ThreadingHTTPServer(('0.0.0.0', 8766), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb('reverse', 'tcp:8766', 'tcp:8766')
    # Ephemeral test device only; these flags do not relax fullscreen activation.
    flags = WORK / 'chrome-command-line'
    flags.write_text('_ --no-first-run --disable-fre --no-default-browser-check\n')
    adb('push', flags, '/data/local/tmp/chrome-command-line')
    adb('shell', 'am', 'set-debug-app', '--persistent', PACKAGE)
    adb('logcat', '-c')
    try:
        print('Android: installing verified baseline APK', flush=True)
        install = adb('install', '-r', context['baselineApk'], timeout=300)
        assert 'Success' in install, install
        cdp = open_page()
        cdp.js('localStorage.setItem("upgrid-ci","preserve-768003111")')
        time.sleep(2)  # Allow the ordinary tab/session persistence task to run.
        cdp.ws.close()
        adb('shell', 'am', 'force-stop', PACKAGE)
        print('Android: installing candidate and checking saved data', flush=True)
        install = adb('install', '-r', context['apk'], timeout=300)
        assert 'Success' in install, install
        cdp = launch_saved_tab()
        assert cdp.js('localStorage.getItem("upgrid-ci")') == 'preserve-768003111'
        checks['install_update_preserves_storage'] = dict(status='passed', evidence=install.strip())
        package_dump = adb('shell', 'dumpsys', 'package', PACKAGE)
        assert re.search(r'versionCode=' + str(context['metadata']['versionCode']) + r'\b', package_dump), 'Installed version differs from tested APK'
        save('package.json', dict(dump=package_dump))
        screenshot('installed')

        def exercise(path, paused=False):
            cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766' + path))
            wait_for(lambda: cdp.js('location.pathname === ' + json.dumps(path) +
                                   ' && typeof state === "function" && v.readyState >= 2'))
            tap('Play')
            wait_for(lambda: cdp.js('probe.frames > 3 && v.currentTime > 0.2'))
            if paused:
                cdp.js('v.pause()')
            before = cdp.js('state()')
            tap('Видеоплеер Upgrid')
            wait_for(lambda: cdp.js('v.matches(":fullscreen")'))
            wait_for(lambda: find('Позиция видео') is not None)
            first = Image.open(io.BytesIO(screenshot(path[1:] + ('-paused' if paused else '') + '-player'))).convert('RGB')
            time.sleep(2)
            during = cdp.js('state()')
            second = Image.open(io.BytesIO(screenshot(path[1:] + '-frame2'))).convert('RGB')
            assert during['videoFullscreen'] and during['width'] == 640 and during['height'] == 360
            assert during['loads'] == before['loads'], 'Video was reloaded on entry'
            assert during['paused'] == paused
            if not paused:
                assert during['frames'] > before['frames'] and during['time'] > before['time']
                w, h = first.size
                region = (w // 4, h * 2 // 5, w * 3 // 4, h * 3 // 5)
                assert max(ImageStat.Stat(first.crop(region)).var) > 100, 'No varied video pixels'
                assert max(ImageStat.Stat(ImageChops.difference(first.crop(region), second.crop(region))).mean) > 1, 'Video pixels did not advance'
            tap('Вернуться на страницу')
            wait_for(lambda: cdp.js('!document.fullscreenElement'))
            after = cdp.js('state()')
            assert after['paused'] == paused and after['loads'] == before['loads']
            save(path[1:] + ('-paused' if paused else '') + '-state.json', dict(before=before, during=during, after=after))
            screenshot(path[1:] + '-returned')

        for path, paused in [('/direct', False), ('/direct', True), ('/clipped', False), ('/shadow', False)]:
            name = path[1:] + ('_paused' if paused else '_playing')
            print('Android: checking ' + name, flush=True)
            try:
                exercise(path, paused)
                checks[name] = dict(status='passed', evidence=path[1:] + ('-paused' if paused else '') + '-state.json')
            except Exception as error:
                checks[name] = dict(status='failed', error=str(error))
                collect_diagnostic(diagnostic_errors, name + '-failure', lambda: screenshot(name + '-failure'))
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        if not context['metadata']['baseline']:
            cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766/clipped'))
            wait_for(lambda: cdp.js('typeof state === "function" && v.readyState >= 2'))
            tap('Play')
            tap('Container fullscreen')
            wait_for(lambda: cdp.js('!!document.fullscreenElement'))
            time.sleep(3)
            assert not cdp.js('v.matches(":fullscreen")')
            assert find('Позиция видео') is None, 'Upgrid overlaid site container'
            screenshot('container-keeps-site-controls')
            checks['container_no_mixed_ui'] = dict(status='passed', evidence='container-keeps-site-controls.png')
            adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(2)
        cdp.ws.close()
        adb('shell', 'am', 'force-stop', PACKAGE)
        cdp = launch_saved_tab()
        assert cdp.js('localStorage.getItem("upgrid-ci")') == 'preserve-768003111'
        screenshot('cold-start')
        checks['cold_start_storage'] = dict(status='passed', evidence='cold-start.png')
    except Exception as error:
        checks['harness'] = dict(status='failed', error=repr(error))
        (EVIDENCE / 'harness-traceback.txt').write_text(traceback.format_exc(), encoding='utf-8')
        collect_diagnostic(diagnostic_errors, 'harness-screenshot', lambda: screenshot('harness-failure'))
        collect_diagnostic(diagnostic_errors, 'harness-ui', lambda:
                           (EVIDENCE / 'failure-ui.xml').write_bytes(ET.tostring(ui(), encoding='utf-8')))
    finally:
        collect_diagnostic(diagnostic_errors, 'logcat', lambda:
                           (EVIDENCE / 'logcat.txt').write_text(adb('logcat', '-d', timeout=20), encoding='utf-8'))
        failed = any(item['status'] != 'passed' for item in checks.values())
        save('results.json', dict(apk=context['metadata'], checks=checks, passed=not failed,
                                 diagnosticErrors=diagnostic_errors,
                                 physicalDevice=False, distributionApproved=False))
        server.shutdown()
    if failed:
        raise SystemExit('Android checks failed; see android-evidence/results.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['fetch', 'run'])
    parser.add_argument('--tag', default='baseline')
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Android tests run only on GitHub Actions, never on the user PC')
    if args.stage == 'fetch':
        fetch(args.tag)
    else:
        run()
