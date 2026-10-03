import copy
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import acceptance
import common
import import_objects as importer
from ninja_cache import HEADER


class AcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.apk = dict(sha256='a'*64, package='com.upgrid.chromium', versionName='test',
                        versionCode=1, signerSha256='b'*64, headSha='c'*40, chromiumRevision='d'*40)
        self.receipt = {**self.apk, 'device': 'real Android device', 'testedAtUtc': '2026-09-30T20:00:00Z',
                        'distributionApproved': True,
                        'checks': {key: {'status': 'passed', 'evidence': 'test evidence'} for key in acceptance.REQUIRED}}
        self.upstream = {'productionApproved': True, 'commit': 'd'*40}

    def test_exact_accepted_apk(self):
        self.assertTrue(acceptance.validate(self.receipt, self.apk, self.upstream))

    def test_every_required_check_and_identity_is_enforced(self):
        for key in acceptance.REQUIRED:
            value = copy.deepcopy(self.receipt)
            del value['checks'][key]
            with self.subTest(check=key), self.assertRaises(ValueError):
                acceptance.validate(value, self.apk, self.upstream)
        for key in self.apk:
            value = {**self.receipt, key: 'wrong'}
            with self.subTest(field=key), self.assertRaises(ValueError):
                acceptance.validate(value, self.apk, self.upstream)
        with self.assertRaises(ValueError):
            acceptance.validate(self.receipt, self.apk, {**self.upstream, 'productionApproved': False})
        with self.assertRaises(ValueError):
            acceptance.validate(self.receipt, self.apk, {**self.upstream, 'commit': 'e'*40})


@unittest.skipUnless(sys.platform == 'linux', 'VPS adapter runs on Linux')
class RelayTests(unittest.TestCase):
    def test_request_cannot_choose_another_repo_package_or_command_path(self):
        from relay_adapter import validate_request
        apk = dict(sha256='a'*64, headSha='b'*40, versionCode=1, bytes=100,
                   package='com.upgrid.chromium', versionName='test')
        request = dict(schema=1, repo='NirvanaEx/android-browser', apk=apk,
                       acceptanceHeadSha='c'*40, tag='upgrid-cloud-build1-'+'a'*12)
        self.assertEqual(validate_request(request), apk)
        for field, value in [('repo', 'other/repo'), ('tag', '../../something'),
                             ('acceptanceHeadSha', 'HEAD;anything')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_request({**request, field: value})
        with self.assertRaises(ValueError):
            validate_request({**request, 'apk': {**apk, 'versionName': '../test'}})

    def test_unapproved_release_fails_before_any_send_or_file_download(self):
        import relay_adapter
        apk = dict(sha256='a'*64, headSha='b'*40, versionCode=1, bytes=100,
                   package='com.upgrid.chromium', versionName='test')
        request = dict(schema=1, repo='NirvanaEx/android-browser', apk=apk, acceptance={},
                       acceptanceHeadSha='c'*40, tag='upgrid-cloud-build1-'+'a'*12)
        with patch.object(relay_adapter, 'service_environment', return_value={}), \
                patch.object(relay_adapter, 'source_json', side_effect=[{}, {'productionApproved': False}]), \
                patch.object(relay_adapter.subprocess, 'run') as call:
            with self.assertRaises(ValueError):
                relay_adapter.dispatch(request)
            call.assert_not_called()


@unittest.skipUnless(sys.platform == 'linux' and shutil.which('ninja'), 'Real Ninja test runs on Linux CI')
class ImportTests(unittest.TestCase):
    object_prefix = 'obj'
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = pathlib.Path(self.tmp.name)
        self.root, self.state = base/'root', base/'state'
        self.src, self.out = self.root/'src', self.root/'src/out/Upgrid'
        self.state.mkdir()
        self.out.mkdir(parents=True)
        self.addCleanup(patch.stopall)
        patch.object(importer, 'ROOT', self.root).start()
        patch.object(importer, 'STATE', self.state).start()
        patch.object(importer, 'reserve').start()
        (self.src/'build/config').mkdir(parents=True)
        for name in ('warning_suppression.txt', 'unsafe_buffers_paths.txt'):
            (self.src/'build/config'/name).write_text('fixture')
        (self.src/'header.h').write_text('fixture')
        compiler = self.src/'emitter'
        compiler.write_text('#!/usr/bin/env python3\nimport pathlib,sys\n'
                            'p=pathlib.Path(sys.argv[1]);p.parent.mkdir(parents=True,exist_ok=True)\n'
                            'p.write_bytes(b"fixture-object")\n'
                            'pathlib.Path(str(p)+".d").write_text(str(p)+": ../../header.h\\n")\n')
        compiler.chmod(0o755)
        actions = []
        graph = []
        for i in range(2):
            name = f'{self.object_prefix}/{i}.o'
            (self.src/f'{i}.cc').write_text('source')
            command = f'../../emitter {name}'
            actions.append({'output': name, 'file': f'../../{i}.cc', 'command': command})
            graph += [f'rule cxx{i}', f'  command = {command}', '  deps = gcc',
                      f'  depfile = {name}.d', f'build {name}: cxx{i} ../../{i}.cc']
        (self.out/'build.ninja').write_text('\n'.join(graph)+'\n')
        subprocess.run(['ninja', '-C', str(self.out)], check=True, capture_output=True)
        logs = {parts[3]: parts for line in (self.out/'.ninja_log').read_text().splitlines()
                if len(parts := line.split('\t')) == 5}
        log_header = (self.out/'.ninja_log').read_text().splitlines()[0]
        self.archives = []
        for i, action in enumerate(actions):
            name = action['output']
            obj = self.out/name
            report = dict(runId='123', headSha='head', snapshotSha256='snapshot', exitCode=0,
                          completed=1, total=1, shard=i,
                          objects=[dict(path=name, sha256=common.sha(obj), bytes=obj.stat().st_size, logRecord=logs[name])])
            report_file = base/f'report{i}.json'
            common.write(report_file, report)
            archive_file = base/f'{i}.tar.gz'
            with tarfile.open(archive_file, 'w:gz') as archive:
                archive.add(report_file, arcname='result.json')
                for path in (name, '.ninja_log', '.ninja_deps'):
                    archive.add(self.out/path, arcname=path)
            self.archives.append(archive_file)
        inputs = [p for p in self.src.rglob('*') if p.is_file() and not p.is_relative_to(self.src/'out')]
        self.manifest = {'inputs': [{'path': p.relative_to(self.src).as_posix(), 'sha256': common.sha(p)} for p in inputs],
                         'shards': [[action] for action in actions]}
        for action in actions:
            (self.out/action['output']).unlink()
        (self.out/'.ninja_deps').write_bytes(HEADER)
        (self.out/'.ninja_log').write_text(log_header+'\n')

    def invoke(self, archives=None):
        return importer.import_objects(self.archives if archives is None else archives,
                                       self.manifest, 'snapshot', '123', 'head')

    def test_parallel_import_accepted_and_changed_header_rebuilds(self):
        self.assertEqual(self.invoke()['importedObjects'], 2)
        self.assertIn('no work to do', subprocess.check_output(['ninja', '-C', str(self.out), '-n'], text=True))
        stamp = max((self.out/f'{self.object_prefix}/{i}.o').stat().st_mtime_ns for i in range(2)) + 2_000_000_000
        os.utime(self.src/'header.h', ns=(stamp, stamp))
        self.assertIn('[2/2]', subprocess.check_output(['ninja', '-C', str(self.out), '-n'], text=True))

    def test_changed_input_rejected_before_output_mutation(self):
        (self.src/'header.h').write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'Compiler input changed'):
            self.invoke()
        self.assertFalse((self.out/f'{self.object_prefix}/0.o').exists())
        self.assertEqual((self.out/'.ninja_deps').read_bytes(), HEADER)

    def test_incomplete_wave_rejected_before_output_mutation(self):
        with self.assertRaisesRegex(RuntimeError, 'Incomplete or overlapping'):
            self.invoke(self.archives[:1])
        self.assertFalse((self.out/f'{self.object_prefix}/0.o').exists())

    def test_corrupt_object_rejected(self):
        stage = self.state/'corrupt'
        stage.mkdir()
        with tarfile.open(self.archives[0]) as archive:
            archive.extractall(stage, filter='data')
        (stage/f'{self.object_prefix}/0.o').write_bytes(b'corrupted')
        with tarfile.open(self.archives[0], 'w:gz') as archive:
            for name in ('result.json', '.ninja_deps', '.ninja_log', f'{self.object_prefix}/0.o'):
                archive.add(stage/name, arcname=name)
        with self.assertRaisesRegex(RuntimeError, 'Object or dependency'):
            self.invoke()
        self.assertFalse((self.out/f'{self.object_prefix}/0.o').exists())


class HostImportTests(ImportTests):
    object_prefix = 'clang_x64/obj'


@unittest.skipUnless(sys.platform == 'linux' and shutil.which('zstd'), 'Archive test runs on Linux CI')
class WorkspaceTests(unittest.TestCase):
    def test_split_workspace_roundtrip_preserves_nanoseconds_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            root, state, assets = base/'root', base/'state', base/'assets'
            for folder in (root, state, assets):
                folder.mkdir()
            original = root/'source'
            original.write_bytes(os.urandom(180000))
            stamp = 1780000000123456789
            os.utime(original, ns=(stamp, stamp))
            (root/'link').symlink_to('source')
            expected = common.sha(original)
            second_packed = threading.Event()
            downloads = threading.Barrier(3)
            original_sha = common.sha
            def hash_part(path):
                result = original_sha(path)
                if pathlib.Path(path).name == 'workspace.0001.tar.zst.part':
                    second_packed.set()
                return result
            def upload(tag, *paths):
                for path in paths:
                    if pathlib.Path(path).name == 'workspace.0000.tar.zst.part':
                        if not second_packed.wait(10):
                            raise RuntimeError('Packing blocked behind the first upload')
                    shutil.copyfile(path, assets/pathlib.Path(path).name)
            def download(tag, name, destination, digest=None):
                if name.endswith('.part'):
                    downloads.wait(timeout=10)
                destination = pathlib.Path(destination)
                destination.mkdir(parents=True, exist_ok=True)
                result = destination/name
                shutil.copyfile(assets/name, result)
                if digest and common.sha(result) != digest:
                    raise RuntimeError('Digest mismatch')
                return result
            with patch.multiple(common, ROOT=root, STATE=state, CHUNK_BYTES=65536), \
                    patch.object(common, 'reserve'), patch.object(common, 'upload', side_effect=upload), \
                    patch.object(common, 'sha', side_effect=hash_part), \
                    patch.object(common, 'download', side_effect=download), patch.dict(os.environ, GITHUB_SHA='head'):
                digest = common.pack_workspace('upgrid-ci-fixture-1-1', 'workspace')
                self.assertGreater(len(common.read(assets/'workspace.json')['parts']), 1)
                original.unlink()
                (root/'link').unlink()
                common.restore_workspace('upgrid-ci-fixture-1-1', 'workspace', digest)
            self.assertEqual(common.sha(original), expected)
            self.assertEqual(original.stat().st_mtime_ns, stamp)
            self.assertTrue((root/'link').is_symlink())

    def test_failed_chunk_upload_never_publishes_checkpoint_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            root, state = base/'root', base/'state'
            root.mkdir()
            state.mkdir()
            (root/'source').write_bytes(os.urandom(180000))
            uploaded = []
            def upload(tag, path):
                uploaded.append(path.name)
                if path.name.endswith('0001.tar.zst.part'):
                    raise RuntimeError('simulated network failure')
            with patch.multiple(common, ROOT=root, STATE=state, CHUNK_BYTES=65536), \
                    patch.object(common, 'reserve'), patch.object(common, 'upload', side_effect=upload):
                with self.assertRaisesRegex(RuntimeError, 'simulated network failure'):
                    common.pack_workspace('upgrid-ci-fixture-1-1', 'workspace',
                                          checkpoint='native-prerequisites-v1')
            self.assertNotIn('workspace.json', uploaded)
            self.assertFalse((state/'workspace.json').exists())
            self.assertTrue((root/'source').exists())


if __name__ == '__main__':
    unittest.main()
