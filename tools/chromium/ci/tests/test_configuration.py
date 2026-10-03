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
    def test_precreated_exact_draft_is_reused_without_create_privilege(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(pipeline.os.environ, {'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'head'}), \
                patch.object(pipeline, 'STATE', Path(directory)), \
                patch.object(pipeline, 'verify_transfer'), patch.object(pipeline, 'create_transfer') as create, \
                patch.object(pipeline, 'upload') as upload:
            pipeline.preflight(None)
            create.assert_not_called()
            upload.assert_called_once()

    def test_transfer_failure_happens_before_build_configuration(self):
        with patch.dict(pipeline.os.environ, {'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1'}), \
                patch.object(pipeline, 'verify_transfer', side_effect=RuntimeError('no draft access')), \
                patch.object(pipeline, 'configure') as configure:
            with self.assertRaisesRegex(RuntimeError, 'no draft access'):
                pipeline.prepare(None)
            configure.assert_not_called()

    def test_transfer_rejects_published_or_wrong_commit_draft(self):
        good = dict(draft=True, tag_name='upgrid-ci-42-1', target_commitish='exact-head')
        with patch.dict(pipeline.os.environ, {'GITHUB_SHA': 'exact-head'}):
            with patch.object(pipeline, 'gh_json', return_value=good):
                self.assertEqual(pipeline.verify_transfer('upgrid-ci-42-1'), good)
            for change in [dict(draft=False), dict(target_commitish='other'), dict(tag_name='upgrid-ci-43-1')]:
                with patch.object(pipeline, 'gh_json', return_value={**good, **change}):
                    with self.assertRaises(RuntimeError):
                        pipeline.verify_transfer('upgrid-ci-42-1')

    def test_cache_preference_fallback_and_revision_isolation(self):
        def release(number, names):
            return {'tag_name': f'upgrid-ci-{number}-1',
                    'assets': [{'name': name} for name in names]}
        prepared = release(2, ['workspace.json', 'plan.json'])
        completed = release(1, ['cache.json', 'apk-verification.json'])
        compatible = {'root': str(pipeline.ROOT), 'chromiumRevision': 'pinned'}
        with patch.object(pipeline, 'download', return_value='manifest'), \
                patch.object(pipeline, 'read', return_value=compatible):
            self.assertEqual(pipeline.select_cached_workspace([prepared, completed], 'pinned'),
                             ('upgrid-ci-1-1', 'cache'))
            self.assertEqual(pipeline.select_cached_workspace([prepared], 'pinned'),
                             ('upgrid-ci-2-1', 'workspace'))
            self.assertEqual(pipeline.select_cached_workspace([prepared, completed], 'other'),
                             ('', 'cache'))
            self.assertEqual(pipeline.select_cached_workspace(
                [release(3, ['workspace.json'])], 'pinned'), ('', 'cache'))

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
