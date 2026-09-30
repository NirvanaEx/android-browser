"""Select generated native inputs from the actual APK dependency graph for review."""
import json
import re
from probe_bundle import OUT, BASE


def main():
    targets = set()
    graph = BASE / 'apk-graph.dot'
    if not graph.read_bytes().rstrip().endswith(b'}'):
        raise RuntimeError('Incomplete Ninja dependency graph')
    for line in graph.read_text().splitlines():
        match = re.match(r'"[^"\n]+" \[label="(gen/[^"\n]+)"', line)
        if match and match[1].endswith(('.h', '.hh', '.hpp', '.inc', '.cc', '.cpp', '.c', '.modulemap')):
            targets.add(match[1])
    if not targets:
        raise RuntimeError('No generated native inputs found')
    if any(any(char in name for char in ' $:\n') or '..' in name.split('/') for name in targets):
        raise RuntimeError('Unexpected target syntax')
    (BASE / 'generated-input-targets.json').write_text(json.dumps(sorted(targets), indent=2) + '\n')
    (OUT / 'upgrid-generated-inputs.ninja').write_text('include build.ninja\n'
        + 'build upgrid_generated_inputs: phony ' + ' '.join(sorted(targets)) + '\n')
    print(json.dumps({'generatedTargets': len(targets),
                      'currentlyMissing': sum(not (OUT / target).exists() for target in targets)}))


if __name__ == '__main__':
    main()
