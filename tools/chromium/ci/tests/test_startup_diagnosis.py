from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import diagnose_android_startup as diagnosis


class StartupDiagnosisTests(unittest.TestCase):
    def test_crash_collection_is_scoped_and_profiles_do_not_reuse_old_crashes(self):
        contents = {
            '/data/tombstones/tombstone_00': b'Cmdline: com.other.app\npid: 1',
            '/data/tombstones/tombstone_01': b'Cmdline: com.upgrid.chromium:sandboxed_process0\npid: 2',
            '/data/tombstones/tombstone_02': b'Cmdline: com.upgrid.chromium.evil\npid: 3',
        }
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(diagnosis.harness, 'adb', return_value='tombstone_00\ntombstone_01\ntombstone_02\ntombstone_01.pb\n../other'), \
                patch.object(diagnosis.harness, 'command', side_effect=lambda *args: contents[args[-1]]):
            seen = set()
            first = diagnosis.collect_tombstones(Path(tmp), seen)
            self.assertEqual([x['name'] for x in first], ['tombstone_01'])
            self.assertEqual(diagnosis.collect_tombstones(Path(tmp), seen), [])
            self.assertEqual([x.name for x in Path(tmp).iterdir()], ['tombstone_01.txt'])

    def test_experiments_preserve_isolation(self):
        base = diagnosis.harness.runtime_flags('default').strip()
        self.assertEqual(diagnosis.PROFILES, {
            'default': base + '\n',
            'graphite-off': base + ' --disable-skia-graphite\n',
            'graphite-off-jitless': base + ' --disable-skia-graphite --js-flags=--jitless\n',
            'graphite-off-low-end': base + ' --disable-skia-graphite --enable-low-end-device-mode\n',
        })


if __name__ == '__main__':
    unittest.main()
