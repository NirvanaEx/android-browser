#!/usr/bin/env python3
"""One-time administrator setup of a forced-command key; never prints secrets."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
INSTALL = r'''
import datetime,json,pathlib,shutil,os,sys
data=json.load(sys.stdin)
public=data['public'].strip()
if not public.startswith('ssh-ed25519 ') or '\n' in public:
    raise ValueError('Invalid public key')
folder=pathlib.Path('/root/.ssh')
target=folder/'authorized_keys'
old=target.read_text() if target.exists() else ''
line='restrict,command="/usr/bin/python3 /root/projects/apk-relay/upgrid-ci/relay_adapter.py" '+public
if public.split()[1] not in old:
    if target.exists():
        shutil.copy2(target, folder/('authorized_keys.upgrid-ci-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S')))
    temporary=folder/'authorized_keys.upgrid-ci.tmp'
    temporary.write_text(old.rstrip('\n')+'\n'+line+'\n')
    temporary.chmod(0o600)
    temporary.replace(target)
doc=pathlib.Path('/root/projects/apk-relay/RELEASING.md')
text=doc.read_text()
if '## Upgrid GitHub CI relay — 2026-10-01' not in text:
    doc.write_text(text+data['documentation'])
print(json.dumps({'installed':True}))
'''


def main():
    if os.environ.get('GITHUB_ACTIONS'):
        raise RuntimeError('Administrator setup must not run in CI')
    gh = shutil.which('gh') or r'C:\Program Files\GitHub CLI\gh.exe'
    private = Path.home()/'.ssh/upgrid-ci-relay_ed25519'
    if not private.exists():
        subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-C', 'upgrid-ci-forced-relay', '-f', str(private)], check=True)
    public = private.with_suffix('.pub').read_text()
    ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', 'vps']
    subprocess.run(ssh+['mkdir -p /root/projects/apk-relay/upgrid-ci'], check=True)
    subprocess.run(['scp', '-q', str(HERE/'relay_adapter.py'), str(HERE/'acceptance.py'),
                    'vps:/root/projects/apk-relay/upgrid-ci/'], check=True)
    doc = '''

## Upgrid GitHub CI relay — 2026-10-01

GitHub Actions invokes `upgrid-ci/relay_adapter.py` through a dedicated SSH
key with `restrict` and a forced command. It cannot open a shell or forward
ports. The bot token remains on this server. Source and tests are maintained
in NirvanaEx/android-browser, tools/chromium/ci; the existing relay and
catalogue retain their implementation and settings.

Publication requires an exact Android acceptance receipt committed to the
repository and an approved Chromium security base. The adapter validates
the GitHub release, APK SHA-256 and metadata, saves a WAL-aware backup,
uses the existing relay with a dry run, records the confirmed message_id,
indexes under app_id=upgrid and runs verify_release.py. Results are kept in
state/upgrid-ci/<APK-SHA256>/. The selfcheck operation sends no messages.

If send-state.json exists without a Telegram receipt, reconcile the prior
send manually. Do not remove that journal or force a resend to bypass this
check. A retry with a confirmed delivery only completes catalogue checking.
GitHub publication is triggered by a reviewed acceptance receipt or an
explicit publish workflow; no new polling or cron schedule is installed.
'''
    subprocess.run(ssh+['python3 -c '+shlex.quote(INSTALL)],
                   input=json.dumps({'public': public, 'documentation': doc}), text=True, check=True)
    host = subprocess.check_output(ssh+['cat /etc/ssh/ssh_host_ed25519_key.pub'], text=True).strip().split()
    known = private.with_name('upgrid-ci-relay-known-hosts')
    known.write_text('193.160.119.15 '+host[0]+' '+host[1]+'\n')
    reply = subprocess.check_output(['ssh', '-i', str(private), '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
                                    '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile='+str(known),
                                    'root@193.160.119.15', 'upgrid-ci-release'],
                                    input=json.dumps({'operation': 'selfcheck'}), text=True)
    if not json.loads(reply).get('ready'):
        raise RuntimeError('Restricted relay health check failed')
    for name, file in [('UPGRID_RELAY_SSH_KEY', private), ('UPGRID_RELAY_KNOWN_HOSTS', known)]:
        subprocess.run([gh, 'secret', 'set', name, '--repo', 'NirvanaEx/android-browser'],
                       input=file.read_text(), text=True, check=True, capture_output=True)
    print(json.dumps({'restrictedRelay': 'ready', 'secretsConfigured': True, 'messagesSent': 0}))


if __name__ == '__main__':
    main()
