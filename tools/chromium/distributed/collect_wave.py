"""Download immutable returned objects for one verified run; never starts builds."""
import argparse
import json
import pathlib
import shutil
import subprocess
import tarfile
from status import api, GH


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('state', type=pathlib.Path)
    args = parser.parse_args()
    state = json.loads(args.state.read_text(encoding='utf-8-sig'))
    run = api('/actions/runs/' + str(state['runId']))
    if run['head_sha'] != state['headSha']:
        raise RuntimeError('Unexpected workflow revision')
    destination = pathlib.Path(state['stagingDirectory']) / ('run-' + str(run['id']))
    destination.mkdir(exist_ok=True)
    release = api('/releases/tags/chromium-distributed-inputs-20260930')
    prefix = f"objects-{run['id']}-{run['run_attempt']}-"
    reports = []
    for asset in release['assets']:
        name = asset['name']
        if not name.startswith(prefix) or not name.endswith('.tar.gz'):
            continue
        if pathlib.Path(name).name != name:
            raise RuntimeError('Invalid asset name')
        target = destination / name
        if not target.exists():
            if shutil.disk_usage(destination).free - asset['size'] < 20 * 1024**3:
                raise RuntimeError('Preserve 20 GiB free on staging disk')
            subprocess.run([GH, 'release', 'download', 'chromium-distributed-inputs-20260930',
                '--repo', 'NirvanaEx/android-browser', '--pattern', name, '--dir', str(destination)], check=True)
        if target.stat().st_size != asset['size']:
            raise RuntimeError('Incomplete download: ' + name)
        with tarfile.open(target) as bundle:
            report = json.load(bundle.extractfile('result.json'))
        if (str(report['runId']) != str(run['id']) or report['headSha'] != state['headSha']
                or report['snapshotSha256'] != state['inputSha256']):
            raise RuntimeError('Mismatched returned artifact')
        reports.append({key: report[key] for key in ('shard', 'completed', 'total', 'exitCode')}
                       | {'archive': str(target)})
        print(json.dumps(reports[-1]), flush=True)
    receipt = {'runId': run['id'], 'headSha': state['headSha'], 'snapshotSha256': state['inputSha256'],
               'runStatus': run['status'], 'reports': reports,
               'completedObjects': sum(item['completed'] for item in reports)}
    (destination / 'download-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({'downloadedShards': len(reports), 'completedObjects': receipt['completedObjects']}))


if __name__ == '__main__':
    main()
