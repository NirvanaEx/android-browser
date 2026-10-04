import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]))
from final_progress import audit_plan, enforce_plan, Monitor, prepare
from runner import run_checked


class FinalProgressTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform == 'linux' and shutil.which('ninja'), 'Real Ninja on GitHub')
    def test_real_ninja_detects_invalidated_import_after_graph_regeneration(self):
        with tempfile.TemporaryDirectory() as directory:
            state = pathlib.Path(directory)
            (state/'input.h').write_text('first')
            (state/'build.ninja').write_text('rule cc\n  command = touch object.o\n  description = CXX object.o\nbuild object.o: cc input.h\n')
            subprocess.run(['ninja', '-C', directory, 'object.o'], check=True, capture_output=True)
            (state/'imported-outputs.json').write_text('["object.o"]')
            with patch.dict(os.environ, GITHUB_ACTIONS='true'):
                # The imported object is clean before a changed input is regenerated.
                prepare(shutil.which('ninja'), directory, state)
                stamp = (state/'object.o').stat().st_mtime_ns + 2_000_000_000
                os.utime(state/'input.h', ns=(stamp, stamp))
                with self.assertRaisesRegex(RuntimeError, 'imported objects'):
                    prepare(shutil.which('ninja'), directory, state)

    def test_repeated_imported_object_is_rejected_even_if_only_one(self):
        report = audit_plan('[1/2] CXX obj/a.o\n[2/2] SOLINK libchrome.so\n', {'obj/a.o'})
        self.assertEqual(report['actionsByType'], {'CXX': 1, 'SOLINK': 1})
        with self.assertRaisesRegex(RuntimeError, 'imported objects'):
            enforce_plan(report)

    def test_bulk_compile_guard_allows_small_remainder_and_link(self):
        for count in (127, 128):
            report = audit_plan('\n'.join(f'[{i+1}/{count}] CC obj/{i}.o' for i in range(count)), set())
            if count == 128:
                with self.assertRaisesRegex(RuntimeError, 'undistributed'):
                    enforce_plan(report)
            else:
                enforce_plan(report)
        enforce_plan(audit_plan('[1/1] SOLINK libchrome.so', set()))
        enforce_plan(audit_plan('ninja: no work to do.', set()))

    def test_audit_saves_failure_evidence_before_rejecting(self):
        with tempfile.TemporaryDirectory() as directory:
            state = pathlib.Path(directory)
            (state/'imported-outputs.json').write_text('["obj/a.o"]')
            with patch.dict(os.environ, GITHUB_ACTIONS='true'), \
                    patch('final_progress.subprocess.check_output', return_value='[1/1] CXX obj/a.o'):
                with self.assertRaises(RuntimeError):
                    prepare('ninja', 'out', state)
            self.assertEqual(json.loads((state/'final-plan.json').read_text())['repeatedImportedObjects'], ['obj/a.o'])

    def test_long_link_and_check_api_failure_retain_diagnostics_without_false_hang(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch('final_progress.subprocess.check_output', return_value='10 1 ninja 00:00:01 20\n11 10 ld.lld 00:10:00 500\n99 1 unrelated 00:00:00 40'), \
                patch.dict(os.environ, GITHUB_SHA='a'*40), \
                patch.object(Monitor, 'request', side_effect=subprocess.TimeoutExpired('gh', 15)):
            monitor = Monitor(directory, {'totalActions': 2})
            monitor.line('[1/2] AR obj/a.a\n')
            monitor.last_action -= 700
            monitor.heartbeat(10)
            report = json.loads((pathlib.Path(directory)/'final-progress.json').read_text())
            self.assertEqual(report['completedActions'], 1)
            self.assertEqual([p['name'] for p in report['processes']], ['ninja', 'ld.lld'])
            self.assertIsNone(report['conclusion'])
            self.assertFalse(report['apkVerified'])
            self.assertGreaterEqual(report['secondsSinceCompletedAction'], 700)

    @unittest.skipUnless(sys.platform == 'linux', 'Child-process lifecycle checked on GitHub')
    def test_runner_preserves_failure_and_captures_last_output(self):
        for code in (0, 3):
            with tempfile.TemporaryDirectory() as directory, \
                    patch.object(Monitor, 'request', return_value={'id': 123}), \
                    patch.dict(os.environ, GITHUB_SHA='a'*40):
                monitor = Monitor(directory, {'totalActions': 1})
                command = [sys.executable, '-c', f'print("[1/1] SOLINK libtest.so"); raise SystemExit({code})']
                if code:
                    with self.assertRaises(subprocess.CalledProcessError):
                        run_checked(command, cwd=directory, observer=monitor)
                else:
                    run_checked(command, cwd=directory, observer=monitor)
                report = json.loads((pathlib.Path(directory)/'final-progress.json').read_text())
                self.assertEqual(report['completedActions'], 1)
                self.assertEqual(report['conclusion'], 'failure' if code else 'success')
                self.assertIn('SOLINK', (pathlib.Path(directory)/'final-build.log').read_text())


if __name__ == '__main__':
    unittest.main()
