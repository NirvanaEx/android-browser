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
import urllib.parse
import xml.etree.ElementTree as ET
import shutil

from acceptance import REQUIRED

REPO = 'NirvanaEx/android-browser'
PACKAGE = 'com.upgrid.chromium'
BASELINE = 'upgrid-android-baseline-768003111'
BASELINE_SHA = '058403f2646440fb2507101beaeba87d8a8470c523561d231b9df494ac942fd2'
WORK = Path('android-test-inputs')
EVIDENCE = Path('android-evidence')
FIXTURES = Path(__file__).with_name('fixtures')


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
    script_results = []
    for script in json.loads((FIXTURES / 'userscripts-inventory.json').read_text(encoding='utf-8')):
        result = dict(id=script['id'], version=script['version'], expectedSha256=script['sha256'], androidExecutionVerified=False)
        try:
            with urllib.request.urlopen(script['source'], timeout=30) as response:
                if urllib.parse.urlsplit(response.url).hostname != 'tampermonkey.neyron.site':
                    raise ValueError('Unexpected userscript download host')
                content = response.read(2 * 1024 * 1024 + 1)
            assert len(content) <= 2 * 1024 * 1024, 'Userscript exceeds fixture size limit'
            result['sha256'] = hashlib.sha256(content).hexdigest()
            assert result['sha256'] == script['sha256'], 'Script changed since reviewed inventory; review new version first'
            destination = WORK / (script['id'] + '.user.js')
            destination.write_bytes(content)
            command('node', '--check', destination, timeout=20)
            result['status'] = 'syntax-passed'
        except Exception as error:
            result.update(status='failed', error=repr(error))
        script_results.append(result)
    save('userscripts-source-verification.json', script_results)


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
 source:v.currentSrc,duration:v.duration,readyState:v.readyState,storage:localStorage.getItem('upgrid-ci')});
if(window.parent !== window){
 setInterval(()=>parent.postMessage({kind:'upgrid-video-state',state:state()},'http://127.0.0.1:8766'),200);
}
if(location.pathname==='/clipped')document.body.className='clipped';
if(location.pathname==='/shadow'){
 const host=document.createElement('div');document.querySelector('#wrap').append(host);
 const s=host.attachShadow({mode:'closed'});s.innerHTML='<style>video{width:100%;height:240px}</style><p>SHADOW SITE UI</p>';s.append(v);
}
if(location.pathname==='/iframe' || location.pathname==='/iframe-same'){
 const origin=location.pathname==='/iframe'?'http://localhost:8766':location.origin;
 document.body.innerHTML='<iframe title="Fixture video" style="width:100%;height:550px" allowfullscreen></iframe>';
 const frame=document.querySelector('iframe');frame.src=origin+'/direct';
 window.iframeState=null;
 window.addEventListener('message',event=>{
  if(event.source===frame.contentWindow && event.origin===origin && event.data?.kind==='upgrid-video-state'){
   window.iframeState={...event.data.state,receivedAt:performance.now()};
  }
 });
 window.state=()=>iframeState && performance.now()-iframeState.receivedAt<3000?iframeState:null;
}
</script>'''


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WORK), **kwargs)

    def do_GET(self):
        if self.path.split('?')[0] in ('/direct', '/clipped', '/shadow', '/iframe', '/iframe-same'):
            data = PAGE.encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            super().do_GET()

    def do_POST(self):
        # The translation fixture reports local counters, never page contents.
        if self.path != '/translation-events':
            self.send_error(404)
            return
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 <= length <= 4096:
            self.send_error(413)
            return
        self.rfile.read(length)
        self.send_response(204)
        self.end_headers()

    def log_message(self, *_):
        pass


class CDP:
    def __init__(self):
        import websocket
        pages = json.load(urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=3))
        save('devtools-targets.json', pages)
        page = next(p for p in pages if p.get('type') == 'page' and ':8766/' in p.get('url', ''))
        self.target_id = page['id']
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


def player_tap(label):
    node = find(label)
    if node is None:
        # A real single tap on the native player surface reveals its controls.
        tap('Видеоплеер Upgrid')
        node = wait_for(lambda: find(label))
    tap_node(node)


def assert_same_video(before, after):
    assert after['source'] == before['source'], 'Selected video source changed'
    assert after['loads'] == before['loads'], 'Video was reloaded'


def assert_playing_advanced(before, after):
    assert not after['paused'], 'Playing video was paused'
    assert after['frames'] > before['frames'], 'No new decoded video frames'
    # The short fixture loops; currentTime need not increase across its end.
    assert abs(after['time'] - before['time']) > 0.01, 'Video clock did not advance'


def app_failures(log):
    failures = []
    # Android native tombstones identify the crashing process before the signal.
    for block in log.split('*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***'):
        if re.search(r'Cmdline: ' + re.escape(PACKAGE) + r'(?=[:\s])', block) and re.search(r'signal \d+ \(SIG', block):
            failures.append(dict(kind='native-crash', detail='\n'.join(line for line in block.splitlines()
                if 'Cmdline:' in line or 'signal ' in line or 'libndk_translation.so' in line)[:4000]))
    for line in log.splitlines():
        if ('ANR in ' + PACKAGE in line or ('ANR in Window{' in line and PACKAGE + '/' in line)
                or ('Process: ' + PACKAGE in line and 'AndroidRuntime' in line)):
            failures.append(dict(kind='anr-or-java-crash', detail=line[:1000]))
    return failures


def acceptance_coverage(checks):
    dependencies = {
        'android_install_and_update': ['install_update_preserves_storage'],
        'saved_data_preserved': ['install_update_preserves_storage'],
        'cold_start_saved_tab': ['cold_start_storage'],
        'real_video_frame': ['direct_playing'],
        'player_enter_exit_playback': ['direct_playing', 'direct_paused', 'clipped_playing',
                                     'shadow_playing', 'iframe_playing', 'iframe-same_playing',
                                     'repeat-native-play-pause', 'container_isolated_video',
                                     'site-fullscreen-repeat'],
        'manual_rotation': ['manual-rotation'],
        'background_and_tab_pause': ['background-pause', 'tab-switch-pause'],
    }
    coverage = {}
    for name in REQUIRED:
        names = dependencies.get(name, [name])
        missing = [item for item in names if item not in checks]
        failed = [item for item in names if item in checks and checks[item]['status'] != 'passed']
        coverage[name] = dict(status='failed' if failed else 'blocked' if missing else 'passed',
                              scenarios=names, missing=missing, failed=failed)
    return coverage


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


def launch_activity():
    resolved = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief',
                   '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', PACKAGE)
    component = next(line.strip() for line in resolved.splitlines()
                     if re.fullmatch(r'[\w.]+/[\w.]+', line.strip()))
    adb('shell', 'am', 'start', '-W', '-n', component)


def launch_saved_tab():
    launch_activity()
    return connect_page()


def connect_page(initial_url=None):
    # DevTools is browser-owned; no desktop browser is launched.
    started = time.monotonic()
    attempts = []
    def discover():
        cdp = None
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
            cdp = CDP()
            # The browser target exists before the renderer is ready. Keep
            # handling late native onboarding while waiting for the test page.
            if not cdp.js('typeof state === "function"'):
                raise RuntimeError('Test page execution context is not ready')
            return cdp
        except Exception as error:
            if cdp is not None:
                cdp.ws.close()
            attempts.append(dict(elapsedSeconds=round(time.monotonic() - started, 1), error=repr(error)))
            save('startup-attempts.json', attempts)
            print('Android: waiting for browser: ' + repr(error), flush=True)
            raise
    # First ARM64 launch includes translation on a fresh x86 emulator.
    cdp = wait_for(discover, timeout=300 if initial_url else 180)
    return cdp


def runtime_flags(profile):
    flags = '_ --no-first-run --disable-fre --no-default-browser-check'
    if profile == 'graphite-off-diagnostic':
        # Pinned gpu_finch_features.cc honors this before feature defaults.
        # Tests the Dawn/Vulkan GPU crash; never changes the signed APK.
        flags += ' --disable-skia-graphite'
    elif profile != 'default':
        raise ValueError('Unknown Android runtime profile')
    return flags + '\n'


def prepare_candidate(context, checks, diagnostic_errors, diagnostic_only=False):
    if diagnostic_only:
        # Do not repeat the known baseline failure. This fresh-device probe
        # cannot establish upgrade preservation or default-runtime acceptance.
        checks['install_update_preserves_storage'] = dict(
            status='blocked', stage='diagnostic-only', error='Baseline not run in diagnostic profile')
        checks['default_runtime_configuration'] = dict(
            status='blocked', error='Graphite disabled for GPU isolation only')
        assert not context['metadata']['baseline'], 'Diagnostic profile requires a candidate APK'
        install = adb('install', '-r', context['apk'], timeout=300)
        assert 'Success' in install, install
        cdp = open_page()
        sentinel = 'diagnostic-only-' + str(context['metadata']['versionCode'])
        cdp.js('localStorage.setItem("upgrid-ci",' + json.dumps(sentinel) + ')')
        return cdp, sentinel
    sentinel = 'preserve-768003111'
    baseline_ready = False
    cdp = None
    try:
        print('Android: installing verified baseline APK', flush=True)
        install = adb('install', '-r', context['baselineApk'], timeout=300)
        assert 'Success' in install, install
        cdp = open_page()
        cdp.js('localStorage.setItem("upgrid-ci",' + json.dumps(sentinel) + ')')
        time.sleep(2)
        baseline_ready = True
    except Exception as error:
        checks['install_update_preserves_storage'] = dict(status='failed', stage='baseline', error=repr(error))
        collect_diagnostic(diagnostic_errors, 'baseline-screenshot', lambda: screenshot('baseline-failure'))
        collect_diagnostic(diagnostic_errors, 'baseline-logcat', lambda:
                           (EVIDENCE / 'baseline-logcat.txt').write_text(adb('logcat', '-d', timeout=20), encoding='utf-8'))
        if context['metadata']['baseline']:
            raise  # Do not retry the same failing APK as if it were a new candidate.
    finally:
        if cdp is not None:
            cdp.ws.close()
    adb('shell', 'am', 'force-stop', PACKAGE)
    print('Android: installing candidate and checking saved data', flush=True)
    install = adb('install', '-r', context['apk'], timeout=300)
    assert 'Success' in install, install
    cdp = None
    if baseline_ready:
        try:
            cdp = launch_saved_tab()
            assert cdp.js('localStorage.getItem("upgrid-ci")') == sentinel
            checks['install_update_preserves_storage'] = dict(status='passed', evidence=install.strip())
        except Exception as error:
            checks['install_update_preserves_storage'] = dict(status='failed', stage='update', error=repr(error))
            collect_diagnostic(diagnostic_errors, 'update-screenshot', lambda: screenshot('update-failure'))
            if cdp is not None:
                cdp.ws.close()
            cdp = None
    if cdp is None:
        # Continue independent player diagnostics without erasing the failed
        # update check or clearing app data. This can never turn the run green.
        print('Android: update check failed; continuing candidate-only diagnostics', flush=True)
        cdp = open_page()
        sentinel = 'candidate-only-' + str(context['metadata']['versionCode'])
        cdp.js('localStorage.setItem("upgrid-ci",' + json.dumps(sentinel) + ')')
    return cdp, sentinel


def run():
    from PIL import Image, ImageChops, ImageStat
    profile = os.environ.get('UPGRID_ANDROID_PROFILE', 'default')
    command_line = runtime_flags(profile)  # Fail before device changes on invalid input.
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
    for fixture in FIXTURES.iterdir():
        if fixture.is_file():
            shutil.copyfile(fixture, WORK / fixture.name)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 8766), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb('reverse', 'tcp:8766', 'tcp:8766')
    # Ephemeral test device only; these flags do not relax fullscreen activation.
    flags = WORK / 'chrome-command-line'
    flags.write_text(command_line)
    save('runtime-profile.json', dict(profile=profile, commandLine=command_line.strip(),
                                      diagnosticOnly=profile != 'default'))
    adb('push', flags, '/data/local/tmp/chrome-command-line')
    adb('shell', 'am', 'set-debug-app', '--persistent', PACKAGE)
    adb('logcat', '-c')
    try:
        cdp, sentinel = prepare_candidate(context, checks, diagnostic_errors,
                                          diagnostic_only=profile != 'default')
        package_dump = adb('shell', 'dumpsys', 'package', PACKAGE)
        assert re.search(r'versionCode=' + str(context['metadata']['versionCode']) + r'\b', package_dump), 'Installed version differs from tested APK'
        save('package.json', dict(dump=package_dump))
        screenshot('installed')

        def exercise(path, paused=False):
            cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766' + path))
            wait_for(lambda: cdp.js('location.pathname === ' + json.dumps(path) +
                                   ' && typeof state === "function" && state()?.readyState >= 2'))
            tap('Play')
            wait_for(lambda: cdp.js('state()?.frames > 3 && state()?.time > 0.2'))
            if paused:
                cdp.js('v.pause()')
            before = cdp.js('state()')
            tap('Видеоплеер Upgrid')
            wait_for(lambda: cdp.js('state()?.videoFullscreen'))
            wait_for(lambda: find('Позиция видео') is not None)
            first = Image.open(io.BytesIO(screenshot(path[1:] + ('-paused' if paused else '') + '-player'))).convert('RGB')
            time.sleep(2)
            during = cdp.js('state()')
            second = Image.open(io.BytesIO(screenshot(path[1:] + '-frame2'))).convert('RGB')
            assert during['videoFullscreen'] and during['width'] == 640 and during['height'] == 360
            assert during['loads'] == before['loads'], 'Video was reloaded on entry'
            assert during['paused'] == paused
            if not paused:
                assert_playing_advanced(before, during)
                w, h = first.size
                region = (w // 4, h * 2 // 5, w * 3 // 4, h * 3 // 5)
                assert max(ImageStat.Stat(first.crop(region)).var) > 100, 'No varied video pixels'
                assert max(ImageStat.Stat(ImageChops.difference(first.crop(region), second.crop(region))).mean) > 1, 'Video pixels did not advance'
            player_tap('Вернуться на страницу')
            wait_for(lambda: cdp.js('!document.fullscreenElement && state() && !state().videoFullscreen'))
            after = cdp.js('state()')
            assert after['paused'] == paused and after['loads'] == before['loads']
            save(path[1:] + ('-paused' if paused else '') + '-state.json', dict(before=before, during=during, after=after))
            screenshot(path[1:] + '-returned')

        for path, paused in [('/direct', False), ('/direct', True), ('/clipped', False), ('/shadow', False),
                             ('/iframe-same', False), ('/iframe', False)]:
            name = path[1:] + ('_paused' if paused else '_playing')
            print('Android: checking ' + name, flush=True)
            try:
                exercise(path, paused)
                checks[name] = dict(status='passed', evidence=path[1:] + ('-paused' if paused else '') + '-state.json')
            except Exception as error:
                checks[name] = dict(status='failed', error=str(error))
                collect_diagnostic(diagnostic_errors, name + '-failure', lambda: screenshot(name + '-failure'))
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')

        def start_direct_player():
            cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766/direct'))
            wait_for(lambda: cdp.js('location.pathname === "/direct" && typeof state === "function" && v.readyState >= 2'))
            tap('Play')
            wait_for(lambda: cdp.js('probe.frames > 3 && !v.paused'))
            before = cdp.js('state()')
            orientation = ui().get('rotation')
            tap('Видеоплеер Upgrid')
            wait_for(lambda: cdp.js('v.matches(":fullscreen")'))
            assert ui().get('rotation') == orientation, 'Entry rotated the screen automatically'
            return before

        def repeat_and_pause():
            before = start_direct_player()
            states = []
            for cycle in range(3):
                player_tap('Пауза')
                wait_for(lambda: cdp.js('v.paused'))
                paused = cdp.js('state()')
                time.sleep(2)
                still = cdp.js('state()')
                assert still['paused'] and abs(still['time'] - paused['time']) < 0.1
                player_tap('Вернуться на страницу')
                wait_for(lambda: cdp.js('!document.fullscreenElement'))
                assert cdp.js('v.paused'), 'Paused video resumed on exit'
                tap('Видеоплеер Upgrid')
                wait_for(lambda: cdp.js('v.matches(":fullscreen")'))
                assert cdp.js('v.paused'), 'Paused video resumed on entry'
                player_tap('Играть')
                wait_for(lambda: cdp.js('!v.paused'))
                playing = cdp.js('state()')
                time.sleep(2)
                advanced = cdp.js('state()')
                assert_same_video(before, advanced)
                assert_playing_advanced(playing, advanced)
                screenshot('repeat-' + str(cycle))
                player_tap('Вернуться на страницу')
                wait_for(lambda: cdp.js('!document.fullscreenElement'))
                assert not cdp.js('v.paused'), 'Playing video paused on exit'
                states.append(dict(paused=paused, playing=advanced, returned=cdp.js('state()')))
                if cycle < 2:
                    tap('Видеоплеер Upgrid')
                    wait_for(lambda: cdp.js('v.matches(":fullscreen")'))
                    assert not cdp.js('v.paused'), 'Playing video paused on entry'
            save('repeat-native-play-pause.json', states)

        def manual_rotation():
            before = start_direct_player()
            original = ui().get('rotation')
            player_tap('Поворот')
            rotated = wait_for(lambda: (r if (r := ui().get('rotation')) != original else None))
            during = cdp.js('state()')
            assert during['videoFullscreen']
            assert_same_video(before, during)
            assert_playing_advanced(before, during)
            screenshot('manual-rotation')
            player_tap('Поворот')
            wait_for(lambda: ui().get('rotation') == original)
            restored = cdp.js('state()')
            assert restored['videoFullscreen'] and not restored['paused']
            assert_same_video(before, restored)
            screenshot('manual-rotation-restored')
            player_tap('Вернуться на страницу')
            wait_for(lambda: cdp.js('!document.fullscreenElement'))
            save('manual-rotation.json', dict(original=original, rotated=rotated, before=before, during=during, restored=restored))

        def site_fullscreen_repeat():
            states = []
            # All entries use a site's button, never the Upgrid toolbar entry.
            # The iframe buttons execute in their own origin with a real tap.
            for path in ('/direct', '/clipped', '/shadow', '/iframe-same', '/iframe'):
                cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766' + path))
                wait_for(lambda: cdp.js('typeof state === "function" && state()?.readyState >= 2'))
                tap('Play')
                wait_for(lambda: cdp.js('state()?.frames > 3 && !state().paused'))
                before = cdp.js('state()')
                for cycle in range(3):
                    started = time.monotonic()
                    tap('Container fullscreen')
                    wait_for(lambda: cdp.js('state()?.videoFullscreen'))
                    wait_for(lambda: find('Позиция видео') is not None)
                    # Includes uiautomator overhead; not a frame-latency benchmark.
                    observed_entry_seconds = time.monotonic() - started
                    time.sleep(1)
                    during = cdp.js('state()')
                    assert_same_video(before, during)
                    assert_playing_advanced(before, during)
                    player_tap('Вернуться на страницу')
                    wait_for(lambda: cdp.js('!state()?.fullscreen'))
                    time.sleep(1)
                    after = cdp.js('state()')
                    assert_same_video(before, after)
                    assert_playing_advanced(during, after)
                    states.append(dict(path=path, cycle=cycle, before=before, during=during,
                                       after=after, observedEntrySeconds=observed_entry_seconds))
                    before = after
                screenshot('site-fullscreen-repeat-' + path[1:])
            save('site-fullscreen-repeat.json', states)

        def address_input_top():
            observations = []
            size = list(map(int, re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))[-1]))
            def address_node():
                return next((n for n in ui().iter('node') if n.get('resource-id') == PACKAGE + ':id/url_bar'), None)
            for cycle in range(2):
                tap_node(wait_for(address_node))
                adb('shell', 'input', 'text', 'upgrid-layout-test')
                wait_for(lambda: re.search(r'(?:mInputShown|mIsInputViewShown|isInputViewShown)=true',
                                          adb('shell', 'dumpsys', 'input_method')))
                node = wait_for(address_node)
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds'))))
                assert len(bounds) == 4 and 0 <= bounds[1] < size[1] / 4
                assert bounds[1] < bounds[3] < size[1] / 2, 'Address field is below the top editing area'
                assert node.get('focused') == 'true' and 'upgrid-layout-test' in node.get('text', '')
                observations.append(dict(cycle=cycle, bounds=bounds, screen=size, keyboardShown=True))
                screenshot('address-input-top-' + str(cycle))
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
            save('address_input_top.json', observations)

        def background_pause():
            nonlocal cdp
            before = start_direct_player()
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            time.sleep(3)
            screenshot('background-home')
            cdp.ws.close()
            cdp = launch_saved_tab()  # No replacement URL and no synthetic Play.
            returned = cdp.js('state()')
            assert returned['paused'], 'Backgrounded video resumed without user Play'
            assert_same_video(before, returned)
            time.sleep(2)
            still = cdp.js('state()')
            assert still['paused'] and abs(still['time'] - returned['time']) < 0.1
            screenshot('background-return-paused')
            if still['videoFullscreen']:
                player_tap('Играть')
            else:
                tap('Play')
            wait_for(lambda: cdp.js('!v.paused && probe.frames > ' + str(still['frames'])))
            save('background-pause.json', dict(before=before, returned=returned, still=still, resumed=cdp.js('state()')))
            if cdp.js('!!document.fullscreenElement'):
                player_tap('Вернуться на страницу')
                wait_for(lambda: cdp.js('!document.fullscreenElement'))

        def tab_switch_pause():
            before = start_direct_player()
            other = None
            try:
                other = cdp.call('Target.createTarget', dict(url='http://127.0.0.1:8766/direct?other-tab=1'))['targetId']
                cdp.call('Target.activateTarget', dict(targetId=other))
                time.sleep(3)
                screenshot('other-tab')
                cdp.call('Target.activateTarget', dict(targetId=cdp.target_id))
                returned = cdp.js('state()')
                assert returned['paused'], 'Tab switching did not preserve manual-resume requirement'
                assert_same_video(before, returned)
                time.sleep(2)
                still = cdp.js('state()')
                assert still['paused'] and abs(still['time'] - returned['time']) < 0.1
                screenshot('tab-return-paused')
                save('tab-switch-pause.json', dict(before=before, returned=returned, still=still,
                                                   selectionMethod='DevTools Target activation; not tab-tray UI proof'))
            finally:
                if other is not None:
                    cdp.call('Target.closeTarget', dict(targetId=other))

        def browser_menu():
            cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766/direct'))
            wait_for(lambda: cdp.js('location.pathname === "/direct" && typeof state === "function"'))
            for cycle in range(3):
                adb('shell', 'input', 'keyevent', 'KEYCODE_MENU')
                wait_for(lambda: find('New tab'))
                assert find('History') is not None, 'Browser menu is incomplete'
                screenshot('browser-menu-' + str(cycle))
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
                wait_for(lambda: find('New tab') is None)
                assert cdp.js('typeof state === "function"'), 'Page stopped responding after menu close'
            save('browser-menu.json', dict(openCloseCycles=3, pageResponsive=True, emptyTabTested=False))

        def empty_tab_menu():
            nonlocal cdp
            # Closing actual tabs is distinct from navigating to about:blank.
            targets = cdp.call('Target.getTargets')['targetInfos']
            pages = [t['targetId'] for t in targets if t.get('type') == 'page']
            for target in pages:
                if target != cdp.target_id:
                    cdp.call('Target.closeTarget', dict(targetId=target))
            cdp.call('Target.closeTarget', dict(targetId=cdp.target_id))
            cdp.ws.close()
            adb('shell', 'am', 'force-stop', PACKAGE)
            launch_activity()
            wait_for(lambda: any(n.get('package') == PACKAGE for n in ui().iter('node')), timeout=90)
            screenshot('empty-start')
            adb('shell', 'input', 'keyevent', 'KEYCODE_MENU')
            wait_for(lambda: find('New tab'))
            screenshot('empty-start-menu')
            tap('New tab')
            screenshot('empty-start-new-tab')
            cdp = open_page()
            assert cdp.js('localStorage.getItem("upgrid-ci")') == sentinel, 'Closing tabs erased site data'
            save('empty_tab_and_menu.json', dict(closedPageTargets=pages, launcherWithoutUrl=True,
                                                nativeNewTabMenuTapped=True, siteStoragePreserved=True))

        def tampermonkey_fixture():
            nonlocal cdp
            # These are real installed userscripts, never Runtime.evaluate
            # substitutes for GM_* or injection through the manager.
            cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766/tampermonkey.html'))
            read = lambda: cdp.js('JSON.parse(document.querySelector("#upgrid-tm-results").textContent)')
            first = wait_for(lambda: (r if (r := read()).get('menuRegistration') else None), timeout=60)
            for key in ('realTampermonkey', 'legacyStorage', 'styleInjection', 'localGmRequest', 'menuRegistration'):
                assert first.get(key) is True, 'Tampermonkey API failed: ' + key
            raw = cdp.js('JSON.parse(document.querySelector("#upgrid-tm-raw-results").textContent)')
            page = cdp.js('JSON.parse(document.querySelector("#upgrid-tm-page-results").textContent)')
            assert raw.get('early') is True and raw.get('intercepted') is True
            assert page.get('pageWorldHook') is True and page.get('pageSawScript') is True
            tap('Проверить выполнение во фрейме')
            def frame_result():
                result = cdp.js('JSON.parse(document.querySelector("iframe").contentDocument.querySelector("#upgrid-tm-results").textContent)')
                return result if result.get('menuRegistration') else None
            frame = wait_for(frame_result)
            assert frame.get('realTampermonkey') is True and frame.get('legacyStorage') is True
            screenshot('tampermonkey-apis-and-frame')
            cdp.call('Page.reload')
            reloaded = wait_for(lambda: (r if (r := read()).get('persistentRunCount', 0) > first['persistentRunCount'] else None))
            cdp.ws.close()
            adb('shell', 'am', 'force-stop', PACKAGE)
            cdp = launch_saved_tab()
            restored = wait_for(lambda: (r if (r := read()).get('persistentRunCount', 0) > reloaded['persistentRunCount'] else None))
            screenshot('tampermonkey-cold-start')
            save('tampermonkey-fixture.json', dict(first=first, frame=frame, raw=raw, page=page, reloaded=reloaded,
                                                 restored=restored, actualUserScriptsTested=False, menuInvocationTested=False))

        def translated_content():
            nonlocal cdp
            cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766/translation.html'))
            def inspect():
                data = cdp.js('({url:location.href,title:document.querySelector("h1")?.textContent,'
                              'excluded:document.querySelector("#excluded")?.textContent,'
                              'input:document.querySelector("input")?.value})')
                return data if data and re.search('[Сс]ад', data.get('title') or '') else None
            first = wait_for(inspect, timeout=90)
            assert first['url'] == 'http://127.0.0.1:8766/translation.html', 'Translation left the original page'
            assert first['excluded'] == 'This sentence must stay in English.'
            assert first['input'] == 'My private garden notes'
            screenshot('translated-content')
            cdp.call('Page.reload')
            wait_for(inspect, timeout=90)
            cdp.ws.close()
            adb('shell', 'am', 'force-stop', PACKAGE)
            cdp = launch_saved_tab()
            restored = wait_for(inspect, timeout=90)
            screenshot('translated-content-cold-start')
            save('translated-content.json', dict(first=first, restored=restored,
                                                 providerVerified=False, siteLanguageExceptionsTested=False))

        for name, operation in [('repeat-native-play-pause', repeat_and_pause),
                                ('site-fullscreen-repeat', site_fullscreen_repeat),
                                ('address_input_top', address_input_top),
                                ('manual-rotation', manual_rotation),
                                ('background-pause', background_pause),
                                ('tab-switch-pause', tab_switch_pause),
                                ('browser-menu', browser_menu),
                                ('empty_tab_and_menu', empty_tab_menu),
                                ('tampermonkey-fixture', tampermonkey_fixture),
                                ('translated-content', translated_content)]:
            print('Android: checking ' + name, flush=True)
            try:
                operation()
                checks[name] = dict(status='passed', evidence=name + '.json')
            except Exception as error:
                checks[name] = dict(status='failed', error=repr(error))
                collect_diagnostic(diagnostic_errors, name + '-failure', lambda: screenshot(name + '-failure'))
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        if not context['metadata']['baseline']:
            cdp.call('Page.navigate', dict(url='http://127.0.0.1:8766/clipped'))
            wait_for(lambda: cdp.js('typeof state === "function" && v.readyState >= 2'))
            tap('Play')
            before = cdp.js('state()')
            tap('Container fullscreen')
            wait_for(lambda: cdp.js('v.matches(":fullscreen")'))
            wait_for(lambda: find('Позиция видео') is not None)
            time.sleep(2)
            during = cdp.js('state()')
            assert_playing_advanced(before, during)
            assert during['loads'] == before['loads'], 'Container promotion reloaded the video'
            screenshot('container-isolated-upgrid-video')
            tap('Вернуться на страницу')
            wait_for(lambda: not cdp.js('document.fullscreenElement'))
            time.sleep(1)
            after = cdp.js('state()')
            assert_playing_advanced(during, after)
            checks['container_isolated_video'] = dict(
                status='passed', evidence='container-isolated-upgrid-video.png',
                before=before, during=during, after=after)
        time.sleep(2)
        cdp.ws.close()
        adb('shell', 'am', 'force-stop', PACKAGE)
        cdp = launch_saved_tab()
        assert cdp.js('localStorage.getItem("upgrid-ci")') == sentinel
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
        log_path = EVIDENCE / 'logcat.txt'
        if log_path.exists():
            crashes = app_failures(log_path.read_text(encoding='utf-8'))
            save('app-failures.json', crashes)
            checks['no_crash_or_anr'] = dict(status='failed' if crashes else 'passed', evidence='app-failures.json')
        else:
            checks['no_crash_or_anr'] = dict(status='blocked', error='logcat unavailable')
        coverage = acceptance_coverage(checks)
        scenario_failed = any(item['status'] != 'passed' for item in checks.values())
        failed = scenario_failed or any(item['status'] != 'passed' for item in coverage.values())
        save('results.json', dict(apk=context['metadata'], checks=checks, passed=not failed,
                                 executedScenariosPassed=not scenario_failed, acceptanceCoverage=coverage,
                                 diagnosticErrors=diagnostic_errors,
                                 runtimeProfile=profile, diagnosticOnly=profile != 'default',
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
