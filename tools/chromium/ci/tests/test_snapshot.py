"""Real archive compatibility checks run only on Linux CI."""
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'distributed'))
import prepare_wave


@unittest.skipUnless(sys.platform == 'linux' and shutil.which('pigz'), 'Cloud compression test')
class SnapshotTests(unittest.TestCase):
    def test_parallel_gzip_remains_readable_with_input_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            src = base / 'src'
            src.mkdir()
            original = src / 'header.h'
            original.write_bytes(b'fixture header\n')
            stamp = 1780000000123456789
            os.utime(original, ns=(stamp, stamp))
            (base / 'llvm-LICENSE.TXT').write_text('fixture license')
            metadata = base / 'manifest.json'
            metadata.write_text('{"schema":1}')
            archive = base / 'snapshot.tar.gz'
            with patch.multiple(prepare_wave, SRC=src, BASE=base), \
                    patch.dict(os.environ, GITHUB_ACTIONS='true'):
                prepare_wave.pack_snapshot(archive, metadata, [{'path': 'header.h'}])
            with tarfile.open(archive, 'r:gz') as reader:
                self.assertEqual(reader.extractfile('src/header.h').read(), original.read_bytes())
                self.assertEqual(reader.getmember('src/header.h').mtime, original.stat().st_mtime)
                self.assertEqual(set(reader.getnames()), {'wave-manifest.json',
                    'LICENSES/llvm-LICENSE.TXT', 'src/header.h'})
