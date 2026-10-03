import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'distributed'))
import pipeline
from prepare_wave import snapshot_headers


class ConfigurationTests(unittest.TestCase):
    def test_optimized_profile_updates_only_owned_ci_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'ci').mkdir()
            output = root / 'output'
            output.mkdir()
            config = {'profile': 'extensions-ci', 'versionName': 'candidate', 'versionCode': 42}
            (root / 'ci/release.json').write_text(json.dumps(config))
            template = ('is_debug = false\nis_java_debug = false\n'
                        'android_override_version_name = "old"\n'
                        'android_override_version_code = "1"\n')
            (root / 'args-extensions.gn').write_text(template)
            (root / 'args-extensions-dev.gn').write_text('preserve developer settings')
            (output / 'args.gn').write_text('old cached debug configuration')
            with patch.object(pipeline, 'TOOLS', root), patch.object(pipeline, 'OUT', output):
                with self.assertRaisesRegex(RuntimeError, 'Unowned'):
                    pipeline.configure()
                self.assertEqual((output / 'args.gn').read_text(), 'old cached debug configuration')
                (output / '.upgrid-build-owner').touch()
                self.assertEqual(pipeline.configure(), config)
            actual = (output / 'args.gn').read_text()
            self.assertIn('is_debug = false', actual)
            self.assertIn('is_java_debug = false', actual)
            self.assertIn('android_override_version_code = "42"', actual)
            self.assertEqual((root / 'args-extensions-dev.gn').read_text(), 'preserve developer settings')

    def test_missing_headers_from_failed_cloud_run_are_packaged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            needed = {
                'third_party/eigen3/src/Eigen/Core',
                'third_party/eigen3/src/unsupported/Eigen/CXX11/Tensor',
                'third_party/spirv-headers/src/include/spirv/unified1/spirv.hpp11',
                'third_party/emoji-segmenter/src/emoji_presentation_scanner.c',
                'base/ordinary.h',
            }
            excluded = {'out/Old/gen/header.h', '.git/internal.h',
                        'node_modules/dependency/header.h', 'unrelated/binary', 'private.key'}
            for name in needed | excluded:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture')
            self.assertEqual({p.relative_to(root).as_posix() for p in snapshot_headers(root)}, needed)


if __name__ == '__main__':
    unittest.main()
