from pathlib import Path
import sys
import subprocess
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_test


class HarnessTests(unittest.TestCase):
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
