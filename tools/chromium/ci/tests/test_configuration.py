import json
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'distributed'))
import pipeline
from prepare_wave import snapshot_headers


class ConfigurationTests(unittest.TestCase):
    def test_warm_cache_skips_empty_host_wave_and_second_workspace_transfer(self):
        for pending, expected in [([], 'native'), ([{'output': 'clang_x64/obj/a.o'}], 'host')]:
            with self.subTest(expected=expected), patch.object(pipeline, 'pending_native', return_value=pending), \
                    patch.object(pipeline, 'native_prerequisites') as prerequisites, \
                    patch.object(pipeline, 'prepare_snapshots') as snapshot, patch.object(pipeline, 'output'):
                args = SimpleNamespace(wave='native')
                pipeline.first_wave(args)
                self.assertEqual(args.wave, expected)
                self.assertEqual(prerequisites.call_count, int(expected == 'native'))
                snapshot.assert_called_once_with(args)
    def test_android_dispatch_rejects_unrelated_candidate_before_dispatch(self):
        with patch.dict(pipeline.os.environ, GITHUB_SHA='head', GITHUB_RUN_ID='42'), \
                patch.object(pipeline, 'read', return_value=dict(headSha='head', runId='42',
                    buildTag='upgrid-ci-42-1')), \
                patch.object(pipeline.subprocess, 'check_output') as dispatch:
            with self.assertRaisesRegex(RuntimeError, 'unrelated APK'):
                pipeline.start_android(SimpleNamespace(tag='upgrid-ci-43-1'))
            dispatch.assert_not_called()

    def test_host_plan_cannot_be_used_as_android_plan(self):
        with patch.dict(pipeline.os.environ, GITHUB_SHA='head', GITHUB_RUN_ID='42'), \
                patch.object(pipeline, 'download'), \
                patch.object(pipeline, 'read', return_value=dict(headSha='head', runId='42', wave='host')):
            with self.assertRaisesRegex(RuntimeError, 'wave mismatch'):
                pipeline.get_plan(SimpleNamespace(tag='upgrid-ci-42-1', wave='native', plan_sha256='hash'))

    def test_completed_checkpoint_can_seed_build_without_native_plan(self):
        release = {'tag_name': 'upgrid-ci-42-1', 'assets': [{'name': 'workspace.json'}]}
        cache = dict(root=str(pipeline.ROOT), chromiumRevision='pinned',
                     checkpoint='native-prerequisites-v1')
        with patch.object(pipeline, 'download', return_value='manifest'), \
                patch.object(pipeline, 'read', return_value=cache):
            self.assertEqual(pipeline.select_cached_workspace([release], 'pinned'),
                             ('upgrid-ci-42-1', 'workspace'))

    def test_checkpoint_finishes_even_when_parallel_native_snapshot_fails(self):
        both_started = threading.Barrier(2)
        checkpoint_finished = threading.Event()
        def checkpoint(*args, **kwargs):
            both_started.wait(timeout=5)
            checkpoint_finished.set()
            return 'workspace-hash'
        def native(args):
            both_started.wait(timeout=5)
            raise RuntimeError('snapshot failed')
        with patch.object(pipeline, 'transfer_tag', return_value='upgrid-ci-42-1'), \
                patch.object(pipeline, 'verify_transfer'), patch.object(pipeline, 'configure'), \
                patch.object(pipeline, 'pending_native', return_value=[]), \
                patch.object(pipeline, 'pack_workspace', side_effect=checkpoint), \
                patch.object(pipeline, 'prepare_native_snapshot', side_effect=native), \
                patch.object(pipeline, 'upload') as upload:
            with self.assertRaisesRegex(RuntimeError, 'snapshot failed'):
                pipeline.prepare_snapshots(SimpleNamespace(shards=40, wave='native', tag=None))
            self.assertTrue(checkpoint_finished.is_set())
            upload.assert_not_called()  # Never publish a usable native plan on failure.

    def test_precreated_exact_draft_is_reused_without_create_privilege(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(pipeline.os.environ, {'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'head'}), \
                patch.object(pipeline, 'STATE', Path(directory)), \
                patch.object(pipeline, 'find_transfer', return_value={'draft': True, 'id': 123}), \
                patch.object(pipeline, 'verify_transfer'), patch.object(pipeline, 'create_transfer') as create, \
                patch.object(pipeline, 'upload') as upload:
            pipeline.preflight(None)
            create.assert_not_called()
            upload.assert_called_once()

    def test_new_draft_uses_create_response_without_stale_listing(self):
        draft = dict(draft=True, id=123, tag_name='upgrid-ci-42-1', target_commitish='head')
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(pipeline.os.environ, {'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'head'}), \
                patch.object(pipeline, 'STATE', Path(directory)), \
                patch.object(pipeline, 'find_transfer', return_value=None) as lookup, \
                patch.object(pipeline, 'create_transfer', return_value=draft), patch.object(pipeline, 'upload'):
            pipeline.preflight(None)
            lookup.assert_called_once()
            self.assertEqual(json.loads((Path(directory) / 'transfer-access.json').read_text())['releaseId'], 123)

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
            with patch.object(pipeline, 'gh_json', return_value=[good]):
                self.assertEqual(pipeline.verify_transfer('upgrid-ci-42-1'), good)
            for change in [dict(draft=False), dict(target_commitish='other'), dict(tag_name='upgrid-ci-43-1')]:
                with patch.object(pipeline, 'gh_json', return_value=[{**good, **change}]):
                    with self.assertRaises(RuntimeError):
                        pipeline.verify_transfer('upgrid-ci-42-1')

    def test_draft_lookup_uses_listing_and_rejects_duplicates(self):
        draft = dict(tag_name='upgrid-ci-42-1', draft=True)
        with patch.object(pipeline, 'gh_json', return_value=[draft]) as query:
            self.assertEqual(pipeline.find_transfer('upgrid-ci-42-1'), draft)
            query.assert_called_once_with('releases?per_page=100&page=1')
        with patch.object(pipeline, 'gh_json', return_value=[draft, draft]):
            with self.assertRaisesRegex(RuntimeError, 'duplicate'):
                pipeline.find_transfer('upgrid-ci-42-1')

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
                'third_party/libc++/src/include/vector',
                'build/linux/sysroot/usr/include/c++/12/string',
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
