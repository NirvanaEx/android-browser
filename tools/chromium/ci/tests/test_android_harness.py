from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_test


class HarnessTests(unittest.TestCase):
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
