import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest
from ninja_cache import HEADER, append_deps, read_deps

NINJA = '/home/neyron/.cache/upgrid/chromium/src/third_party/ninja/ninja'


class CacheTests(unittest.TestCase):
    def test_merge_is_accepted_by_real_ninja_and_header_changes_invalidate_it(self):
        with tempfile.TemporaryDirectory(prefix='upgrid-ninja-import-') as directory:
            root = pathlib.Path(directory)
            local, remote = root / 'local', root / 'remote'
            for folder in (local, remote):
                folder.mkdir()
                (folder / 'header.h').write_text('unchanged')
                (folder / 'emitter.py').write_text("from pathlib import Path\nPath('obj.o').write_bytes(b'test-object')\nPath('obj.o.d').write_text('obj.o: header.h\\n')\n")
                (folder / 'build.ninja').write_text('rule cxx\n  command = python3 emitter.py\n  depfile = obj.o.d\n  deps = gcc\nbuild obj.o: cxx\n')
            subprocess.run([NINJA, '-C', str(remote)], check=True, capture_output=True)
            _, remote_records = read_deps(remote / '.ninja_deps')
            (local / '.ninja_deps').write_bytes(HEADER)
            append_deps(local / '.ninja_deps', [], {'unrelated.o': (1, ['unrelated.h'])})
            paths, _ = read_deps(local / '.ninja_deps')
            shutil.copy2(remote / 'obj.o', local / 'obj.o')
            stamp = (local / 'obj.o').stat().st_mtime_ns
            append_deps(local / '.ninja_deps', paths, {'obj.o': (stamp, remote_records['obj.o'][1])})
            shutil.copy2(remote / '.ninja_log', local / '.ninja_log')
            result = subprocess.check_output([NINJA, '-C', str(local), '-n'], text=True)
            self.assertIn('no work to do', result)
            os.utime(local / 'header.h', ns=(stamp + 2_000_000_000, stamp + 2_000_000_000))
            result = subprocess.check_output([NINJA, '-C', str(local), '-n'], text=True)
            self.assertNotIn('no work to do', result)
            self.assertIn('python3 emitter.py', result)

    def test_truncated_log_is_rejected_without_repairing_original(self):
        with tempfile.TemporaryDirectory() as directory:
            file = pathlib.Path(directory) / 'deps'
            data = HEADER + b'\x20\x00\x00\x00short'
            file.write_bytes(data)
            with self.assertRaises(ValueError):
                read_deps(file)
            self.assertEqual(file.read_bytes(), data)


if __name__ == '__main__':
    unittest.main()
