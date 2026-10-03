from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import source_cache


class SourceCacheTests(unittest.TestCase):
    def test_cache_requires_completed_matching_setup_and_all_tool_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tools = root / 'tools'
            tools.mkdir()
            (tools / 'prepare.py').write_text('setup v1')
            (tools / 'upstream.json').write_text('{"commit":"pinned"}')
            for name in (*source_cache.INPUTS, 'src/third_party/android_sdk/public/build-tools/36/aapt',
                         'src/third_party/android_sdk/public/build-tools/36/apksigner'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture tool')
            self.assertFalse(source_cache.reusable(root, tools))
            source_cache.save(root, tools)
            self.assertTrue(source_cache.reusable(root, tools))
            for path in (root / 'src/DEPS', root / source_cache.INPUTS[-1], tools / 'prepare.py', tools / 'upstream.json'):
                previous = path.read_text()
                path.write_text('{"commit":"changed"}')
                self.assertFalse(source_cache.reusable(root, tools), str(path))
                path.write_text(previous)
            (root / source_cache.INPUTS[-1]).unlink()
            self.assertFalse(source_cache.reusable(root, tools))
