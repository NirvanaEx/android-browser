"""Refresh exact pending compiler commands from the existing Ninja graph."""
import json
import re
import subprocess
from probe_bundle import SRC, OUT, BASE


def main():
    ninja = str(SRC / 'third_party/ninja/ninja')
    cache = SRC.parent
    commands_path = cache / 'wave2-pending-commands.txt'
    database_path = cache / 'wave2-compdb.json'
    with commands_path.open('w') as stream:
        subprocess.run([ninja, '-C', str(OUT), '-n', '-v', 'chrome_public_apk'], stdout=stream, check=True)
    # Toolchain rules live in subninja scopes, so `-t rules` need not list them.
    toolchains = [OUT / 'toolchain.ninja', *OUT.glob('*/toolchain.ninja')]
    rules = [match[1] for path in toolchains for match in
             re.finditer(r'^rule (\S+)$', path.read_text(), re.MULTILINE)]
    cxx_rules = [rule for rule in rules if rule == 'cxx' or rule.endswith('_cxx')]
    if 'cxx' not in cxx_rules:
        raise RuntimeError('Native compiler rule not found')
    with database_path.open('w') as stream:
        # Some unrelated Rust commands contain byte-escaped non-ASCII metadata.
        # Export actual C++ rules so their command bytes remain exact UTF-8.
        subprocess.run([ninja, '-C', str(OUT), '-t', 'compdb', *cxx_rules], stdout=stream, check=True)
    commands = {re.sub(r'^\[\d+/\d+\] ', '', line) for line in commands_path.read_text().splitlines()
                if re.match(r'^\[\d+/\d+\] ', line)}
    database = json.loads(database_path.read_text())
    actions = [item for item in database if item['command'] in commands and
               item['command'].split(' ', 1)[0].endswith('/clang++') and item['output'].endswith('.o')
               and item['output'].startswith('obj/')]
    if not actions:
        raise RuntimeError('No pending C++ commands found; inspect the full graph')
    (BASE / 'pending-cxx-wave2.json').write_text(json.dumps(actions) + '\n')
    (BASE / 'wave2-pending-summary.json').write_text(json.dumps({'pendingCxx': len(actions),
        'pendingCommandLines': len(commands)}, indent=2) + '\n')
    print(json.dumps({'pendingCxx': len(actions)}), flush=True)


if __name__ == '__main__':
    main()
