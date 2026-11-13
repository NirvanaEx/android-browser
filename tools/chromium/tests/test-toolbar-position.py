#!/usr/bin/env python3
"""Run the actual pinned/patched position method on GitHub; not Android UI proof."""
import argparse
import importlib.util
import json
import os
import pathlib
import subprocess
import tempfile
import urllib.request

HERE = pathlib.Path(__file__).resolve().parents[1]
HARNESS = """
public class ToolbarPositionTest {
    @interface ControlsPosition { int TOP = 0, BOTTOM = 1, NONE = 2; }
    @interface StateTransition {
        int NONE = 0, SNAP_TO_TOP = 1, SNAP_TO_BOTTOM = 2,
            ANIMATE_TO_TOP = 3, ANIMATE_TO_BOTTOM = 4;
    }
    interface ToolbarPositionAndSource { int TOP_LONG_PRESS = 0, BOTTOM_LONG_PRESS = 2; }
    static int source;
    static int computeToolbarPositionAndSource() { return source; }
    static class Flag {
        boolean value;
        boolean isEnabled() { return value; }
        boolean getValue() { return value; }
    }
    static class ChromeFeatureList {
        static Flag sAndroidBottomToolbarV2 = new Flag();
        static Flag sAndroidBottomToolbarV2ForceBottomForFocusedOmnibox = new Flag();
    }
    // METHOD
    static void expect(int expected, int actual, String label) {
        if (actual != expected) throw new AssertionError(label + ": " + actual);
    }
    public static void main(String[] args) {
        int cases = 0;
        // All feature/pref/page combinations must keep an editing field on top.
        for (int bits = 0; bits < 256; bits++) {
            ChromeFeatureList.sAndroidBottomToolbarV2.value = (bits & 1) != 0;
            ChromeFeatureList.sAndroidBottomToolbarV2ForceBottomForFocusedOmnibox.value = (bits & 2) != 0;
            for (int position = 0; position < 3; position++) {
                expect(position == ControlsPosition.TOP ? StateTransition.NONE : StateTransition.SNAP_TO_TOP,
                    calculateStateTransition((bits & 4) != 0, (bits & 8) != 0,
                        (bits & 16) != 0, true, (bits & 32) != 0, (bits & 64) != 0,
                        (bits & 128) != 0, position), "focused-field-must-stay-top");
                cases++;
            }
        }
        // Leaving editing restores the existing browsing preference.
        expect(StateTransition.SNAP_TO_BOTTOM,
            calculateStateTransition(false, false, false, false, false, false, false, ControlsPosition.TOP),
            "restore-bottom-browsing");
        expect(StateTransition.NONE,
            calculateStateTransition(false, false, false, false, false, false, true, ControlsPosition.TOP),
            "retain-top-browsing");
        source = ToolbarPositionAndSource.BOTTOM_LONG_PRESS;
        expect(StateTransition.ANIMATE_TO_BOTTOM,
            calculateStateTransition(true, false, false, false, false, false, false, ControlsPosition.TOP),
            "unfocused-user-preference");
        System.out.println("Passed " + cases + " editing combinations and browsing restoration.");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=pathlib.Path)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Java checks run only on GitHub Actions; no local compilation.')
    spec = importlib.util.spec_from_file_location('overlay', HERE / 'apply-overlay.py')
    overlay = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(overlay)
    revision = json.loads((HERE / 'upstream.json').read_text())['commit']
    url = f'https://raw.githubusercontent.com/chromium/chromium/{revision}/{overlay.TOOLBAR_POSITION}'
    with urllib.request.urlopen(url, timeout=45) as response:
        original = response.read().decode()
    for label, source in [('baseline', original), ('patched', overlay.render_toolbar_position(original))]:
        start = source.index('    static @StateTransition int calculateStateTransition(')
        end = source.index('\n    private void updateViewOffset(', start)
        with tempfile.TemporaryDirectory(prefix='upgrid-toolbar-') as directory:
            work = pathlib.Path(directory)
            java = work / 'ToolbarPositionTest.java'
            java.write_text(HARNESS.replace('    // METHOD', source[start:end]))
            subprocess.run([args.jdk / 'bin/javac', '-J-Xmx128m', '--release', '17', str(java)], check=True)
            result = subprocess.run([args.jdk / 'bin/java', '-Xmx64m', '-cp', str(work),
                                     'ToolbarPositionTest'], capture_output=True, text=True)
            if label == 'baseline':
                if result.returncode == 0 or 'AssertionError: focused-field-must-stay-top' not in result.stderr:
                    raise RuntimeError('Regression did not reproduce on the pinned original method')
                print('Pinned original reproduces bottom editing regression.')
            else:
                if result.returncode:
                    raise RuntimeError(result.stderr)
                print(result.stdout.strip())


if __name__ == '__main__':
    main()
