import os
import shutil
import hashlib
import json
import sys
import time
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit
import websocket

BASE = 'http://127.0.0.1:18610'
OUT = Path(os.environ.get('BANANA_AUDIT_OUT', str(Path(__file__).resolve().parents[3] / 'build/banana-device-audit')))
OUT.mkdir(parents=True, exist_ok=True)
HOSTS = {'127.0.0.1', 'localhost'}

def url_info(url):
    u = urlsplit(url)
    safe_path = u.path if u.path.endswith(('.js', '.css')) or u.path.startswith(('/sp/', '/spnbf')) else '[sha256:' + hashlib.sha256(u.path.encode()).hexdigest()[:12] + ']'
    return {'host': u.hostname, 'path': safe_path, 'scheme': u.scheme}

def targets():
    return json.load(urllib.request.urlopen(BASE + '/json/list', timeout=5))

class Tab:
    def __init__(self, target):
        assert urlsplit(target.get('url', '')).hostname in HOSTS
        self.id = target['id']
        self.ws = websocket.create_connection(target['webSocketDebuggerUrl'], suppress_origin=True, timeout=10)
        self.seq = 0
        self.events = []
        self.requests = {}
        self.call('Page.enable')
        self.call('Network.enable')

    def event(self, item):
        method = item.get('method')
        p = item.get('params', {})
        if method == 'Network.requestWillBeSent':
            self.requests[p['requestId']] = {'url': url_info(p['request']['url']), 'type': p.get('type')}
        elif method == 'Network.loadingFailed':
            self.events.append({'event': 'failed', **self.requests.get(p['requestId'], {}), 'error': p.get('errorText'), 'blockedReason': p.get('blockedReason')})
        elif method == 'Network.responseReceived':
            self.events.append({'event': 'response', 'url': url_info(p['response']['url']), 'type': p.get('type'), 'status': p['response'].get('status'), 'cached': p['response'].get('fromDiskCache', False)})
        elif method == 'Page.windowOpen':
            self.events.append({'event': 'windowOpen', 'url': url_info(p['url']), 'userGesture': p.get('userGesture')})
        elif method == 'Page.frameNavigated':
            f = p.get('frame', {})
            self.events.append({'event': 'frameNavigated', 'main': not f.get('parentId'), 'url': url_info(f.get('url', ''))})

    def call(self, method, params=None, timeout=15):
        self.seq += 1
        seq = self.seq
        self.ws.send(json.dumps({'id': seq, 'method': method, 'params': params or {}}))
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            self.ws.settimeout(max(.1, until-time.monotonic()))
            msg = json.loads(self.ws.recv())
            if msg.get('id') == seq:
                if 'error' in msg:
                    raise RuntimeError(str(msg['error']))
                return msg.get('result', {})
            self.event(msg)
        raise TimeoutError(method)

    def wait(self, seconds):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            self.ws.settimeout(max(.1, until-time.monotonic()))
            try:
                self.event(json.loads(self.ws.recv()))
            except websocket.WebSocketTimeoutException:
                break

    def js(self, expression):
        r = self.call('Runtime.evaluate', {'expression': expression, 'returnByValue': True}, timeout=12)
        if 'exceptionDetails' in r:
            return {'evaluation_exception': r['exceptionDetails'].get('text')}
        return r.get('result', {}).get('value')


class Monitor(Tab):
    def __init__(self, target):
        self.existing = {x['id'] for x in targets()}
        self.owned = {target['id']}
        self.frames = set()
        self.created = set()
        self.latest = {}
        self.aux = []
        super().__init__(target)
        self.refresh_frames()
        self.call('Target.setDiscoverTargets', {'discover': True})

    def refresh_frames(self):
        def visit(tree):
            self.frames.add(tree['frame']['id'])
            for child in tree.get('childFrames', []):
                visit(child)
        visit(self.call('Page.getFrameTree')['frameTree'])

    def event(self, item):
        super().event(item)
        method = item.get('method', '')
        p = item.get('params', {})
        if method in ('Target.targetCreated', 'Target.targetInfoChanged'):
            info = p.get('targetInfo', {})
            ident = info.get('targetId')
            if ident in self.existing or info.get('type') != 'page':
                return
            if ident in self.created or info.get('openerId') in self.owned or info.get('openerFrameId') in self.frames:
                self.created.add(ident)
                self.owned.add(ident)
                data = {'event': method, 'id': ident, 'url': url_info(info.get('url', '')), 'canAccessOpener': info.get('canAccessOpener')}
                self.latest[ident] = data['url']
                self.aux.append(data)
        elif method == 'Target.targetDestroyed' and p.get('targetId') in self.created:
            self.aux.append({'event': method, 'id': p['targetId']})

    def reset(self):
        self.events.clear()
        self.aux.clear()

    def root(self):
        return self.call('Page.getFrameTree')['frameTree']['frame']

    def close_created(self):
        for ident in list(self.created):
            try:
                urllib.request.urlopen(BASE + '/json/close/' + ident, timeout=3).read()
            except Exception:
                pass
        self.created.clear()
