#!/usr/bin/env python3
"""Publish only an accepted cloud APK, then use the existing VPS relay and verifier."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
from common import PROJECT, REPO, STATE, cloud_only, download, read, run, sha, write
from acceptance import validate


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-tag', required=True)
    args = parser.parse_args()
    cloud_only()
    metadata = read(download(args.build_tag, 'apk-verification.json', STATE))
    if not re.fullmatch('[0-9a-f]{64}', metadata['sha256']):
        raise RuntimeError('Invalid APK digest')
    accepted = read(PROJECT / 'tools/chromium/ci/acceptance' / (metadata['sha256'] + '.json'))
    validate(accepted, metadata, read(PROJECT / 'tools/chromium/upstream.json'))
    apk = download(args.build_tag, 'ChromePublic.apk', STATE, metadata['sha256'])
    if apk.stat().st_size != metadata['bytes']:
        raise RuntimeError('APK size mismatch')
    # Transport only a project-specific key, never the user's general root key.
    for name in ('UPGRID_RELAY_SSH_KEY', 'UPGRID_RELAY_KNOWN_HOSTS'):
        if not os.environ.get(name):
            raise RuntimeError('Missing dedicated relay credential: ' + name)
    private = pathlib.Path(os.environ['RUNNER_TEMP']) / 'upgrid-relay-key'
    known_hosts = private.with_name('upgrid-relay-known-hosts')
    private.write_text(os.environ['UPGRID_RELAY_SSH_KEY'] + '\n')
    private.chmod(0o600)
    known_hosts.write_text(os.environ['UPGRID_RELAY_KNOWN_HOSTS'] + '\n')
    tag = f"upgrid-cloud-build{metadata['versionCode']}-{metadata['sha256'][:12]}"
    target = STATE / f"Upgrid-{metadata['versionName']}-build{metadata['versionCode']}-arm64.apk"
    if target.name != str(target.relative_to(STATE)) or '/' in metadata['versionName'] or '\\' in metadata['versionName']:
        raise RuntimeError('Unsafe APK filename')
    apk.rename(target)
    try:
        # A new accepted release has immutable content. Retry only an existing
        # matching draft; a prior published release goes straight to relay dedup.
        result = subprocess.run(['gh', 'release', 'view', tag, '--repo', REPO, '--json', 'isDraft,assets,targetCommitish'],
                                capture_output=True, text=True)
        if result.returncode:
            run('gh', 'release', 'create', tag, '--repo', REPO, '--draft', '--target', metadata['headSha'],
                '--title', 'Upgrid ' + metadata['versionName'], '--notes', 'Android acceptance passed for the attached APK.')
            run('gh', 'release', 'upload', tag, target, STATE / 'apk-verification.json', '--repo', REPO)
            run('gh', 'release', 'edit', tag, '--repo', REPO, '--draft=false', '--prerelease', '--latest=false')
        elif json.loads(result.stdout)['isDraft']:
            raise RuntimeError('Existing draft may be incomplete; inspect it before retrying publication')
        request = {'schema': 1, 'repo': REPO, 'tag': tag, 'apk': metadata, 'acceptance': accepted}
        reply = subprocess.run(['ssh', '-i', str(private), '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + str(known_hosts),
            'root@193.160.119.15', 'upgrid-ci-release'], input=json.dumps(request),
            text=True, capture_output=True, check=True, timeout=1800)
        receipt = json.loads(reply.stdout)
        if not receipt.get('verified') or not receipt.get('message_id'):
            raise RuntimeError('Relay did not confirm both Telegram message and release verification')
        write(STATE / 'telegram-release-receipt.json', receipt)
    finally:
        private.unlink(missing_ok=True)
        known_hosts.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
