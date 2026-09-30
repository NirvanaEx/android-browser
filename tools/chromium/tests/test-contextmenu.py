#!/usr/bin/env python3
"""Run real menu dispatch against existing Chromium Robolectric dependencies.

The before/after run proves the upstream code reproduces the assertion path.
This does not replace Android extension/translation acceptance.
"""
import argparse
import ast
import pathlib
import subprocess
import tempfile
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
MEDIATOR = 'chrome/android/java/src/org/chromium/chrome/browser/contextmenu/ContextMenuMediator.java'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--src', type=pathlib.Path,
                        default=pathlib.Path('~/.cache/upgrid/chromium/src'))
    args = parser.parse_args()
    src = args.src.expanduser().resolve()
    out = src / 'out/Upgrid'
    helper = out / 'bin/helper/content_junit_tests'
    tree = ast.parse(helper.read_text())
    paths = next(ast.literal_eval(node.value) for node in tree.body
                 if isinstance(node, ast.Assign)
                 and any(isinstance(t, ast.Name) and t.id == 'classpath' for t in node.targets))
    cp = [str((helper.parent / p).resolve()) for p in paths]
    cp.append(str(out / 'obj/chrome/android/chrome_java.javac.jar'))
    cp.append(str(out / 'obj/components/embedder_support/android/context_menu_java.javac.jar'))
    jdk = src / 'third_party/jdk/current/bin'
    with tempfile.TemporaryDirectory(prefix='upgrid-context-menu-') as directory:
        root = pathlib.Path(directory)
        props = root / 'properties.jar'
        with zipfile.ZipFile(props, 'w') as bundle:
            bundle.writestr('com/android/tools/test_config.properties',
                            f'android_resource_apk={out}/obj/content/public/android/content_junit_tests.robo.ap_\n')
            bundle.writestr('robolectric.properties',
                            'application=android.app.Application\nsdk=36\n'
                            'shadows=org.chromium.testing.local.CustomShadowApplicationPackageManager\n')
        for variant in ('upstream', 'fixed'):
            classes = root / variant
            classes.mkdir()
            source = classes / 'ContextMenuMediator.java'
            source.write_bytes(subprocess.check_output(['git', 'show', f'HEAD:{MEDIATOR}'], cwd=src)
                               if variant == 'upstream' else (src / MEDIATOR).read_bytes())
            subprocess.run([jdk / 'javac', '-J-Xmx256m', '-proc:none', '-cp', ':'.join(cp),
                            '-d', classes, source, HERE / 'ContextMenuDispatchTest.java'], check=True)
            test_jar = root / f'{variant}.jar'
            with zipfile.ZipFile(test_jar, 'w') as bundle:
                for file in classes.rglob('*.class'):
                    bundle.write(file, file.relative_to(classes))
            command = [jdk / 'java', '-Xmx512m', '-ea', '-XX:+EnableDynamicAgentLoading',
                       '--add-opens=java.base/java.io=ALL-UNNAMED',
                       '--add-opens=java.base/java.lang=ALL-UNNAMED',
                       '--add-opens=java.base/java.util=ALL-UNNAMED',
                       f'-Drobolectric.dependency.dir={src}/third_party/robolectric/cipd/lib',
                       '-Drobolectric.offline=true', '-Drobolectric.resourcesMode=binary',
                       f'-Ddir.source.root={src}', f'-Djava.library.path={out}/robolectric_x64',
                       '-cp', ':'.join([str(test_jar), str(props), *cp]),
                       'org.chromium.testing.local.JunitTestMain',
                       '--json-config', str(root / f'{variant}.json')]
            subprocess.run([*command, '--list-tests', '--gtest-filter', '*ContextMenuDispatchTest.*',
                            '--shadows-allowlist', str(src / 'testing/android/junit/shadows-allowlist.txt')],
                           cwd=src, check=True, capture_output=True, text=True)
            command += ['--json-results', str(root / f'{variant}-results.json')]
            result = subprocess.run(command, cwd=src, capture_output=True, text=True)
            print(f'=== {variant} ===\n{result.stdout}\n{result.stderr}', flush=True)
            if variant == 'upstream':
                if result.returncode == 0 or 'Extension routed to browser ID 0' not in result.stdout:
                    raise RuntimeError('Did not reproduce the specific upstream dispatch failure')
            elif result.returncode:
                raise RuntimeError('Fixed dispatch tests failed')


if __name__ == '__main__':
    main()
