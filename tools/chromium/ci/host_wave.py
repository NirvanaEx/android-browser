"""Build only the direct inputs of host compiler actions before distributing them.

Ninja remains responsible for transitive dependencies (including bootstrap tools).
Never replace missing generated inputs with guessed files or stale timestamps.
"""
import re


def query_inputs(text, expected):
    result = {}
    current, section = None, None
    for line in text.splitlines():
        if line and not line.startswith(' '):
            current = line.removesuffix(':')
            if current not in expected or current in result:
                raise RuntimeError('Unexpected/duplicate Ninja query target: ' + current)
            result[current] = set()
            section = None
        elif line.startswith('  ') and not line.startswith('    '):
            section = line.strip().split(':', 1)[0]
        elif line.startswith('    ') and section in ('input', 'validations'):
            name = line.strip()
            name = re.sub(r'^\|\|? ', '', name)
            if not re.fullmatch(r'[A-Za-z0-9_./+@=-]+', name):
                raise RuntimeError('Unsupported Ninja dependency syntax: ' + name)
            result[current].add(name)
    if set(result) != set(expected):
        raise RuntimeError('Missing Ninja query targets')
    return set().union(*result.values()) if result else set()


def write_inputs(ninja, actions, out, state):
    targets = sorted({action['output'] for action in actions})
    dependencies = set()
    for start in range(0, len(targets), 128):
        batch = targets[start:start + 128]
        query = state / 'host-query.txt'
        with query.open('w') as stream:
            ninja('-t', 'query', *batch, stdout=stream)
        dependencies.update(query_inputs(query.read_text(), set(batch)))
    # A dependency may itself be a compiler output (bootstrap tool). Ninja must
    # build it, even if it occurs in the original candidate wave as well.
    wrapper = out / 'upgrid-host-inputs.ninja'
    wrapper.write_text('include build.ninja\nbuild upgrid_host_inputs: phony '
                       + ' '.join(sorted(dependencies)) + '\n')
    return wrapper, len(targets), len(dependencies)
