"""Build only the direct inputs of host compiler actions before distributing them.

Ninja remains responsible for transitive dependencies (including bootstrap tools).
Never replace missing generated inputs with guessed files or stale timestamps.
"""
import re
import os


def query_batches(targets, budget):
    batch, size = [], 0
    for target in targets:
        cost = len(os.fsencode(target)) + 9  # NUL plus argv pointer.
        if cost > budget:
            raise RuntimeError('Ninja target exceeds argument budget')
        if batch and size + cost > budget:
            yield batch
            batch, size = [], 0
        batch.append(target)
        size += cost
    if batch:
        yield batch


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
    # Reading the Chromium graph for every 128 targets wastes minutes. Batch
    # by the actual OS argv budget, with room reserved for the environment.
    limit = os.sysconf('SC_ARG_MAX') if hasattr(os, 'sysconf') else 32768
    environment_bytes = sum(len(os.fsencode(k)) + len(os.fsencode(v)) + 2 for k, v in os.environ.items())
    budget = min(256 * 1024, (limit - environment_bytes - 16384) // 2)
    if budget < 4096:
        raise RuntimeError('Insufficient argument space for Ninja queries')
    for batch in query_batches(targets, budget):
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
