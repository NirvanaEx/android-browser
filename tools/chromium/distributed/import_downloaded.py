"""Import verified downloaded shards serially, retaining per-shard completion receipts."""
import argparse
import json
import pathlib
import sys
import import_wave
from probe_bundle import BASE


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('receipt', type=pathlib.Path)
    parser.add_argument('--snapshot-prefix', default='wave1')
    args = parser.parse_args()
    receipt = json.loads(args.receipt.read_text())
    imported = 0
    for item in receipt['reports']:
        if not item['completed']:
            continue
        previous = BASE / 'before-remote-import' / f"run-{receipt['runId']}-shard-{item['shard']}-receipt.json"
        if previous.exists():
            accepted = json.loads(previous.read_text())
            if not accepted['applied'] or accepted['headSha'] != receipt['headSha']:
                raise RuntimeError('Unexpected prior import receipt')
            imported += accepted['verifiedObjects']
            continue
        archive = args.receipt.parent / pathlib.PureWindowsPath(item['archive']).name
        sys.argv = ['import_wave.py', str(archive), '--run-id', str(receipt['runId']),
                    '--head-sha', receipt['headSha'], '--snapshot-prefix', args.snapshot_prefix, '--apply']
        import_wave.main()
        imported += item['completed']
    print(json.dumps({'runId': receipt['runId'], 'importedObjects': imported}), flush=True)


if __name__ == '__main__':
    main()
