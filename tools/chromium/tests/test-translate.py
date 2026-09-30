#!/usr/bin/env python3
"""Exercise the actual Java translator bridge with Chromium's JNI test hooks.

Requires an already built chrome_java and content_junit_tests dependency set.
Native permissions/event delivery and Google translation still need Android QA.
"""
import argparse
import ast
import json
import pathlib
import subprocess
import tempfile
import zipfile

HERE = pathlib.Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--src', type=pathlib.Path,
                        default=pathlib.Path('~/.cache/upgrid/chromium/src'))
    src = parser.parse_args().src.expanduser().resolve()
    out = src / 'out/Upgrid'
    helper = out / 'bin/helper/content_junit_tests'
    paths = next(ast.literal_eval(node.value) for node in ast.parse(helper.read_text()).body
                 if isinstance(node, ast.Assign)
                 and any(isinstance(t, ast.Name) and t.id == 'classpath' for t in node.targets))
    cp = [str((helper.parent / p).resolve()) for p in paths]
    cp.append(str(out / 'obj/chrome/android/chrome_java.javac.jar'))
    config = json.loads((out / 'gen/chrome/android/chrome_java.build_config.json').read_text())
    cp += [str((out / p).resolve()) for p in config['javac_full_classpath']]
    cp = list(dict.fromkeys(cp))
    jdk = src / 'third_party/jdk/current/bin'
    with tempfile.TemporaryDirectory(prefix='upgrid-translate-tests-') as directory:
        root = pathlib.Path(directory)
        classes = root / 'classes'
        classes.mkdir()
        classpath_args = root / 'classpath.args'
        classpath_args.write_text('-cp\n' + ':'.join(cp) + '\n')
        subprocess.run([jdk / 'javac', '-J-Xmx256m', '-proc:none', '@' + str(classpath_args),
                        '-d', classes, HERE / 'UpgridTranslateTest.java'], check=True)
        test_jar = root / 'test.jar'
        with zipfile.ZipFile(test_jar, 'w') as bundle:
            for file in classes.rglob('*.class'):
                bundle.write(file, file.relative_to(classes))
            bundle.writestr('com/android/tools/test_config.properties',
                            f'android_resource_apk={out}/obj/content/public/android/content_junit_tests.robo.ap_\n')
            bundle.writestr('robolectric.properties',
                            'application=android.app.Application\nsdk=36\n'
                            'shadows=org.chromium.testing.local.CustomShadowApplicationPackageManager\n')
        classpath_args.write_text('-cp\n' + ':'.join([str(test_jar), *cp]) + '\n')
        command = [jdk / 'java', '-Xmx512m', '-ea', '-XX:+EnableDynamicAgentLoading',
                   '--add-opens=java.base/java.io=ALL-UNNAMED',
                   '--add-opens=java.base/java.lang=ALL-UNNAMED',
                   '--add-opens=java.base/java.util=ALL-UNNAMED',
                   f'-Drobolectric.dependency.dir={src}/third_party/robolectric/cipd/lib',
                   '-Drobolectric.offline=true', '-Drobolectric.resourcesMode=binary',
                   f'-Ddir.source.root={src}', f'-Djava.library.path={out}/robolectric_x64',
                   '@' + str(classpath_args),
                   'org.chromium.testing.local.JunitTestMain',
                   '--json-config', str(root / 'tests.json')]
        subprocess.run([*command, '--list-tests', '--gtest-filter', '*UpgridTranslateTest.*',
                        '--shadows-allowlist', str(src / 'testing/android/junit/shadows-allowlist.txt')],
                       cwd=src, check=True, capture_output=True, text=True)
        subprocess.run([*command, '--json-results', str(root / 'results.json')], cwd=src, check=True)


if __name__ == '__main__':
    main()
