from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_test


class HarnessTests(unittest.TestCase):
    def test_graphite_diagnostic_never_claims_update_or_default_runtime_acceptance(self):
        context = dict(apk='candidate.apk', metadata=dict(baseline=False, versionCode=768003113))
        checks = {}
        with patch.object(android_test, 'adb', return_value='Success') as adb, \
                patch.object(android_test, 'open_page', return_value=Mock()):
            android_test.prepare_candidate(context, checks, {}, diagnostic_only=True)
        self.assertEqual(checks['default_runtime_configuration']['status'], 'blocked')
        self.assertNotEqual(android_test.acceptance_coverage(checks)['saved_data_preserved']['status'], 'passed')
        adb.assert_called_once_with('install', '-r', 'candidate.apk', timeout=300)
        self.assertEqual(android_test.runtime_flags('graphite-off-diagnostic'),
                         android_test.runtime_flags('default').rstrip() + ' --disable-skia-graphite\n')
        with self.assertRaises(ValueError):
            android_test.runtime_flags('arbitrary-flags')

    def test_unexecuted_acceptance_is_blocked_even_if_smoke_checks_pass(self):
        coverage = android_test.acceptance_coverage({'direct_playing': {'status': 'passed'}})
        self.assertEqual(coverage['real_video_frame']['status'], 'passed')
        self.assertEqual(coverage['tampermonkey_scripts']['status'], 'blocked')
        self.assertEqual(coverage['player_enter_exit_playback']['status'], 'blocked')
        coverage = android_test.acceptance_coverage({'background-pause': {'status': 'passed'},
                                                     'tab-switch-pause': {'status': 'failed'}})
        self.assertEqual(coverage['background_and_tab_pause']['status'], 'failed')

    def test_crash_detection_includes_renderer_but_ignores_other_apps(self):
        marker = '*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***'
        crash = '\nF DEBUG: Cmdline: com.upgrid.chromium:sandboxed_process0\nF DEBUG: signal 11 (SIGSEGV)\n'
        log = marker + crash + marker + crash.replace('com.upgrid.chromium', 'com.other.browser')
        failures = android_test.app_failures(log)
        self.assertEqual(len(failures), 1)
        self.assertEqual(failures[0]['kind'], 'native-crash')
        self.assertEqual(android_test.app_failures('ActivityManager: ANR in com.other.browser'), [])
        self.assertTrue(android_test.app_failures('WindowManager: ANR in Window{abc u0 com.upgrid.chromium/Activity}'))

    def test_loop_boundary_is_playback_but_frozen_frames_are_not(self):
        before = dict(paused=False, frames=350, time=23.9)
        android_test.assert_playing_advanced(before, dict(paused=False, frames=380, time=1.9))
        for after in [dict(paused=False, frames=350, time=1.9),
                      dict(paused=True, frames=380, time=1.9),
                      dict(paused=False, frames=380, time=23.9)]:
            with self.subTest(after=after), self.assertRaises(AssertionError):
                android_test.assert_playing_advanced(before, after)

    def test_video_identity_check_rejects_reload_and_source_replacement(self):
        before = dict(source='http://fixture/sample.mp4', loads=1)
        android_test.assert_same_video(before, dict(before))
        for after in [dict(source='http://fixture/other.mp4', loads=1),
                      dict(source=before['source'], loads=2)]:
            with self.subTest(after=after), self.assertRaises(AssertionError):
                android_test.assert_same_video(before, after)

    def test_baseline_failure_does_not_hide_candidate_or_pass_update_check(self):
        context = dict(baselineApk='old.apk', apk='new.apk', metadata=dict(baseline=False, versionCode=768003112))
        candidate = Mock()
        checks, errors = {}, {}
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(android_test, 'EVIDENCE', Path(tmp)), \
                patch.object(android_test, 'adb', return_value='Success') as adb, \
                patch.object(android_test, 'screenshot'), \
                patch.object(android_test, 'open_page', side_effect=[RuntimeError('translator crash'), candidate]), \
                patch.object(android_test, 'launch_saved_tab') as restore:
            result, sentinel = android_test.prepare_candidate(context, checks, errors)
        self.assertIs(result, candidate)
        self.assertEqual(sentinel, 'candidate-only-768003112')
        self.assertEqual(checks['install_update_preserves_storage']['status'], 'failed')
        self.assertEqual(checks['install_update_preserves_storage']['stage'], 'baseline')
        restore.assert_not_called()
        adb.assert_any_call('install', '-r', 'new.apk', timeout=300)
        self.assertFalse(any('clear' in call.args or 'uninstall' in call.args for call in adb.call_args_list))

    def test_failed_update_restoration_stays_failed_during_candidate_diagnostics(self):
        baseline, candidate = Mock(), Mock()
        checks = {}
        context = dict(baselineApk='old.apk', apk='new.apk', metadata=dict(baseline=False, versionCode=768003112))
        with patch.object(android_test, 'adb', return_value='Success'), \
                patch.object(android_test, 'screenshot'), patch.object(android_test.time, 'sleep'), \
                patch.object(android_test, 'open_page', side_effect=[baseline, candidate]), \
                patch.object(android_test, 'launch_saved_tab', side_effect=RuntimeError('restore failed')):
            result, sentinel = android_test.prepare_candidate(context, checks, {})
        self.assertIs(result, candidate)
        self.assertEqual(checks['install_update_preserves_storage']['status'], 'failed')
        self.assertEqual(checks['install_update_preserves_storage']['stage'], 'update')
        self.assertTrue(sentinel.startswith('candidate-only-'))

    def test_late_native_prompt_is_handled_while_waiting_for_renderer(self):
        stalled, ready = Mock(), Mock()
        stalled.js.side_effect = TimeoutError('Renderer not ready')
        ready.js.return_value = True
        with patch.object(android_test, 'CDP', side_effect=[stalled, ready]), \
                patch.object(android_test, 'dismiss_notification_prompt', side_effect=[False, True]) as prompt, \
                patch.object(android_test, 'adb', return_value='@chrome_devtools_remote') as adb, \
                patch.object(android_test, 'save'), patch.object(android_test.time, 'sleep'):
            self.assertIs(android_test.connect_page(), ready)
        stalled.ws.close.assert_called_once()
        self.assertEqual(prompt.call_count, 2)
        # A late dialog may be dismissed, but saved-tab restoration cannot be
        # replaced by an explicit navigation, even after a renderer timeout.
        self.assertFalse(any(call.args[:3] == ('shell', 'am', 'start') for call in adb.call_args_list))

    def test_null_ui_dump_cannot_reuse_previous_screen(self):
        calls = []
        def fake_adb(*args, **kwargs):
            calls.append(args)
            if args[:2] == ('shell', 'cat'):
                raise subprocess.CalledProcessError(1, 'cat')
            return ''
        with patch.object(android_test, 'adb', side_effect=fake_adb):
            with self.assertRaises(subprocess.CalledProcessError):
                android_test.ui()
        self.assertEqual(calls[0], ('shell', 'rm', '-f', '/sdcard/upgrid-ui.xml'))

    def test_only_browser_notification_rationale_is_declined(self):
        tree = ET.fromstring('<hierarchy><node package="com.upgrid.chromium" '
                             'resource-id="com.upgrid.chromium:id/notification_permission_rationale_title"/>'
                             '<node package="com.upgrid.chromium" resource-id="com.upgrid.chromium:id/negative_button" '
                             'bounds="[10,20][110,80]"/></hierarchy>')
        with patch.object(android_test, 'ui', return_value=tree), \
                patch.object(android_test, 'screenshot'), patch.object(android_test, 'adb') as adb:
            self.assertTrue(android_test.dismiss_notification_prompt())
            adb.assert_called_once_with('shell', 'input', 'tap', 60, 50)

    def test_site_decline_button_is_not_treated_as_startup_prompt(self):
        tree = ET.fromstring('<hierarchy><node package="com.upgrid.chromium" text="No thanks" '
                             'bounds="[10,20][110,80]"/></hierarchy>')
        with patch.object(android_test, 'ui', return_value=tree), patch.object(android_test, 'adb') as adb:
            self.assertFalse(android_test.dismiss_notification_prompt())
            adb.assert_not_called()

    def test_unresponsive_adb_has_finite_timeout_and_reports_failure(self):
        with patch.object(android_test.subprocess, 'check_output',
                          side_effect=subprocess.TimeoutExpired('adb', 45)) as call:
            with self.assertRaises(subprocess.TimeoutExpired):
                android_test.adb('shell', 'dumpsys', 'package', 'example')
            self.assertEqual(call.call_args.kwargs['timeout'], 45)

    def test_logcat_failure_does_not_prevent_remaining_evidence(self):
        errors = {}
        with patch.object(android_test, 'adb',
                          side_effect=subprocess.TimeoutExpired('adb', 20)):
            android_test.collect_diagnostic(errors, 'logcat', lambda: android_test.adb('logcat', '-d'))
        self.assertIn('TimeoutExpired', errors['logcat'])
        with patch.object(android_test, 'save') as save:
            android_test.collect_diagnostic(errors, 'results', lambda: android_test.save('results.json', {}))
            save.assert_called_once()
        self.assertNotIn('results', errors)

    def test_leaf_accessibility_node_is_a_successful_wait_result(self):
        node = ET.fromstring('<node text="Play" bounds="[10,20][110,80]"/>')
        with patch.object(android_test.time, 'sleep') as sleep:
            self.assertIs(android_test.wait_for(lambda: node), node)
            sleep.assert_not_called()

    def test_tap_uses_reported_bounds_not_hardcoded_screen_coordinates(self):
        node = ET.fromstring('<node text="Play" bounds="[10,20][110,80]"/>')
        with patch.object(android_test, 'find', return_value=node), \
                patch.object(android_test, 'adb') as call:
            android_test.tap('Play')
            call.assert_called_once_with('shell', 'input', 'tap', 60, 50)

    def test_transient_ui_errors_are_retried_but_do_not_fabricate_a_result(self):
        attempts = iter([RuntimeError('UI not ready'), False, 'ready'])
        def operation():
            value = next(attempts)
            if isinstance(value, Exception):
                raise value
            return value
        with patch.object(android_test.time, 'sleep'):
            self.assertEqual(android_test.wait_for(operation), 'ready')


if __name__ == '__main__':
    unittest.main()
