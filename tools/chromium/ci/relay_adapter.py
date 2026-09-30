#!/usr/bin/env python3
"""Forced-command adapter installed beside the existing VPS relay.

Only accepts an exact, accepted Upgrid release; no arbitrary remote commands.
Bot credentials stay on the VPS. An uncertain send is never retried blindly.
"""
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import time
from acceptance import validate

HERE = Path('/root/projects/apk-relay')
REPO = 'NirvanaEx/android-browser'
STATE = HERE / 'state/upgrid-ci'


def write(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2)+'\n')
    temporary.replace(path)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def api(endpoint):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/{endpoint}'], text=True))


def source_json(path, revision):
    data = api(f'contents/{path}?ref={revision}')
    return json.loads(base64.b64decode(data['content']))


def service_environment():
    pid = subprocess.check_output(['systemctl', 'show', '-p', 'MainPID', '--value', 'apk-catalog'], text=True).strip()
    if not pid.isdigit() or pid == '0':
        raise RuntimeError('Catalogue service unavailable')
    return dict(part.split('=', 1) for part in Path('/proc', pid, 'environ').read_text().split('\0') if '=' in part)


def validate_request(request):
    if request.get('schema') != 1 or request.get('repo') != REPO:
        raise ValueError('Unsupported release source')
    apk = request['apk']
    if (not re.fullmatch('[a-f0-9]{64}', apk.get('sha256', ''))
            or type(apk.get('versionCode')) is not int
            or type(apk.get('bytes')) is not int or not 1 <= apk['bytes'] < 2_000_000_000
            or not re.fullmatch('[a-f0-9]{40}', apk.get('headSha', ''))
            or not re.fullmatch('[a-f0-9]{40}', request.get('acceptanceHeadSha', ''))
            or not re.fullmatch(r'[A-Za-z0-9.+_-]{1,100}', apk.get('versionName', ''))
            or apk.get('package') != 'com.upgrid.chromium'):
        raise ValueError('Invalid APK identity')
    expected = f"upgrid-cloud-build{apk['versionCode']}-{apk['sha256'][:12]}"
    if request.get('tag') != expected:
        raise ValueError('Release tag does not match APK')
    return apk


def dispatch(request):
    env = service_environment()
    if request.get('operation') == 'selfcheck':
        return {'ready': all(shutil.which(name) for name in ('gh', 'aapt', 'python3'))
                and all((HERE/name).is_file() for name in ('relay.sh', 'verify_release.py', 'AGENTS.md', 'RELEASING.md')),
                'protocol': 1, 'messagesSent': 0}
    apk = validate_request(request)
    revision = request['acceptanceHeadSha']
    accepted = source_json('tools/chromium/ci/acceptance/'+apk['sha256']+'.json', revision)
    upstream = source_json('tools/chromium/upstream.json', revision)
    if accepted != request['acceptance']:
        raise ValueError('Acceptance differs from the reviewed repository receipt')
    validate(accepted, apk, upstream)
    release = api('releases/tags/'+request['tag'])
    assets = [asset for asset in release['assets'] if asset['name'].endswith('.apk')]
    if (release['draft'] or release['target_commitish'] != apk['headSha'] or len(assets) != 1
            or assets[0]['size'] != apk['bytes']):
        raise ValueError('Published release identity differs')
    asset = assets[0]
    if Path(asset['name']).name != asset['name'] or '\\' in asset['name']:
        raise ValueError('Invalid asset filename')
    directory = STATE/apk['sha256']
    directory.mkdir(parents=True, exist_ok=True)
    with (STATE/'release.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        receipt_path = directory/'receipt.json'
        if receipt_path.exists():
            return json.loads(receipt_path.read_text())
        journal = directory/'send-state.json'
        source_key = re.sub('[^A-Za-z0-9._-]', '_', REPO+'_'+request['tag'])
        telegram_path = HERE/'state/receipts'/f"{source_key}_{asset['id']}.receipt.json"
        if journal.exists() and not telegram_path.exists():
            raise RuntimeError('Previous send may have reached Telegram; reconcile it before retrying')
        target = directory/asset['name']
        if not target.exists():
            if shutil.disk_usage(directory).free < apk['bytes'] + 2*1024**3:
                raise RuntimeError('Insufficient relay storage')
            subprocess.run(['gh', 'release', 'download', request['tag'], '--repo', REPO,
                            '--pattern', asset['name'], '--dir', str(directory)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if target.stat().st_size != apk['bytes'] or sha(target) != apk['sha256']:
            raise ValueError('Downloaded APK checksum mismatch')
        badging = subprocess.check_output(['aapt', 'dump', 'badging', str(target)], text=True)
        package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        if not package or (package[1], package[2], package[3]) != (apk['package'], str(apk['versionCode']), apk['versionName']):
            raise ValueError('Downloaded APK metadata mismatch')
        db = Path(env.get('CATALOG_DB', HERE/'state/catalog.sqlite3'))
        backup = directory/'catalog-before.sqlite3'
        if not backup.exists():
            with sqlite3.connect(f'file:{db}?mode=ro', uri=True) as live, sqlite3.connect(backup) as copy:
                live.backup(copy)
        if not telegram_path.exists():
            subprocess.run([str(HERE/'relay.sh'), '--repo='+REPO, '--tag='+request['tag'],
                            '--title=Upgrid', '--dry-run'], cwd=HERE, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
            write(journal, {'phase': 'sending', 'sha256': apk['sha256'], 'startedUtcEpoch': time.time()})
            with (directory/'relay-private.log').open('w') as log:
                subprocess.run([str(HERE/'relay.sh'), '--repo='+REPO, '--tag='+request['tag'], '--title=Upgrid'],
                               cwd=HERE, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=1900)
        if not telegram_path.exists():
            raise RuntimeError('No durable Telegram delivery receipt; do not resend automatically')
        telegram = json.loads(telegram_path.read_text())
        message = telegram.get('result', {})
        if not telegram.get('ok') or not message.get('message_id'):
            raise RuntimeError('Telegram did not confirm message_id')
        telegram['catalog'].update(app_id='upgrid', version=apk['versionName'], build_number=str(apk['versionCode']),
                                   package_name=apk['package'], platform='android', release_type='test')
        write(telegram_path, telegram)
        sys.path.insert(0, str(HERE))
        from catalog import Catalog
        store = Catalog(db, env.get('CATALOG_APPS', HERE/'apps.json'))
        rid = store.record(message, env['CHAT_ID'], **telegram['catalog'])
        # The periodic importer may have indexed the relay receipt before its
        # exact APK metadata was attached. Only this delivered row is updated.
        with store.db:
            store.db.execute('UPDATE releases SET version=?,build_number=?,package_name=? WHERE id=? AND app_id=?',
                             (apk['versionName'], str(apk['versionCode']), apk['package'], rid, 'upgrid'))
        store.db.close()
        verification = subprocess.run(['python3', str(HERE/'verify_release.py'), '--app', 'upgrid',
            '--version', apk['versionName'], '--sha256', apk['sha256'], '--size', str(apk['bytes'])],
            cwd=HERE, capture_output=True, text=True, check=True, timeout=180)
        checked = json.loads(verification.stdout)
        if not checked.get('ok'):
            raise RuntimeError('Mandatory catalogue verification failed')
        result = {'verified': True, 'message_id': message['message_id'], 'sha256': apk['sha256'],
                  'catalogue': 'https://t.me/my_android_apk_release_bot?start=app_upgrid',
                  'checks': checked, 'verifiedAtUtcEpoch': time.time()}
        write(receipt_path, result)
        target.unlink()  # Only this verified temporary download; Telegram retains its copy.
        return result


def main():
    os.umask(0o077)
    if os.environ.get('SSH_ORIGINAL_COMMAND') != 'upgrid-ci-release':
        raise ValueError('Only upgrid-ci-release is allowed')
    raw = sys.stdin.buffer.read(65537)
    if len(raw) > 65536:
        raise ValueError('Request too large')
    print(json.dumps(dispatch(json.loads(raw))))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # subprocess errors and URLs can contain relay credentials; keep those
        # in the server-only log rather than emitting them to GitHub Actions.
        print(json.dumps({'verified': False, 'error': type(error).__name__}), file=sys.stderr)
        raise SystemExit(1) from None
