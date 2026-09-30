"""Atomically publish only named CI files to the dedicated experimental branch."""
import json
import base64
import pathlib
import subprocess
import sys

GH = r'C:\Program Files\GitHub CLI\gh.exe'
REPO = 'repos/NirvanaEx/android-browser'
BRANCH = 'codex/chromium-distributed'
ROOT = pathlib.Path(__file__).resolve().parents[3]


def api(endpoint, payload=None, method=None):
    command = [GH, 'api', REPO + endpoint]
    if payload is not None:
        command += ['--input', '-']
    if method:
        command += ['--method', method]
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            text=True, encoding='utf-8', capture_output=True, check=True)
    return json.loads(result.stdout)


def main():
    message, *files = sys.argv[1:]
    if not files:
        raise RuntimeError('Explicit files required')
    entries = []
    for name in files:
        file = (ROOT / name).resolve()
        if not file.is_relative_to(ROOT) or not (name.startswith('tools/chromium/')
                or name.startswith('.github/workflows/chromium-')
                or name.startswith('app/src/main/res/drawable/')):
            raise RuntimeError('Refusing unrelated file')
        if file.suffix not in ('.py', '.json', '.gn', '.md', '.yml', '.sh', '.java', '.cc', '.inc', '.js', '.cjs', '.xml', '.png'):
            raise RuntimeError('Refusing unexpected source type')
        if file.suffix == '.png':
            blob = api('/git/blobs', {'encoding': 'base64', 'content': base64.b64encode(file.read_bytes()).decode()})
            entries.append({'path': name, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
        else:
            entries.append({'path': name, 'mode': '100644', 'type': 'blob', 'content': file.read_text(encoding='utf-8')})
    head = api('/git/ref/heads/' + BRANCH)['object']['sha']
    tree = api('/git/commits/' + head)['tree']['sha']
    changed = api('/git/trees', {'base_tree': tree, 'tree': entries})['sha']
    commit = api('/git/commits', {'message': message, 'tree': changed, 'parents': [head]})
    api('/git/refs/heads/' + BRANCH, {'sha': commit['sha'], 'force': False}, 'PATCH')
    print(json.dumps({'commit': commit['sha'], 'files': files}))


if __name__ == '__main__':
    main()
