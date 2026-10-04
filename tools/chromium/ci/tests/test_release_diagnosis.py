import io
import pathlib
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from diagnose_release import extract, selected


class ReleaseDiagnosisTests(unittest.TestCase):
    def test_only_exact_logs_and_libraries_are_selected(self):
        for name in ('./src/out/Upgrid/.ninja_log',
                     'src/out/Upgrid/lib.unstripped/libchrome_combined.so'):
            self.assertTrue(selected(name))
        for name in ('/src/out/Upgrid/.ninja_log', '../src/out/Upgrid/.ninja_log',
                     'src/out/Upgrid/lib.unstripped/../../libchrome.so',
                     'src/third_party/llvm-build/bin/clang', 'src/out/Other/.ninja_log'):
            self.assertFalse(selected(name))

    def test_selective_copy_never_extracts_unselected_files_or_links(self):
        for symlink in (False, True):
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode='w') as archive:
                other = tarfile.TarInfo('../../unwanted')
                other.size = 4
                archive.addfile(other, io.BytesIO(b'data'))
                log = tarfile.TarInfo('./src/out/Upgrid/.ninja_log')
                if symlink:
                    log.type, log.linkname = tarfile.SYMTYPE, '/tmp/unwanted'
                    archive.addfile(log)
                else:
                    log.size = 4
                    archive.addfile(log, io.BytesIO(b'log!'))
            stream.seek(0)
            with tempfile.TemporaryDirectory() as directory:
                destination = pathlib.Path(directory)
                if symlink:
                    with self.assertRaisesRegex(RuntimeError, 'member'):
                        extract(stream, destination)
                else:
                    files = extract(stream, destination)
                    self.assertEqual(len(files), 1)
                    self.assertEqual(files[0].read_bytes(), b'log!')
                    self.assertEqual(len(list(destination.rglob('*.*'))), 1)


if __name__ == '__main__':
    unittest.main()
