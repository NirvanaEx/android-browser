import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / 'distributed'))
from host_wave import query_inputs, write_inputs, query_batches
from action_paths import object_path, host_object


class HostPlanTests(unittest.TestCase):
    def test_queries_are_bounded_without_reloading_graph_for_tiny_batches(self):
        targets = [f'clang_x64/obj/target{i}.o' for i in range(9000)]
        batches = list(query_batches(targets, 256 * 1024))
        self.assertEqual([name for batch in batches for name in batch], targets)
        self.assertLess(len(batches), 4)
        for batch in batches:
            self.assertLessEqual(sum(len(name.encode()) + 9 for name in batch), 256 * 1024)
        with self.assertRaises(RuntimeError):
            list(query_batches(['long_target'], 4))

    def test_explicit_implicit_order_only_and_validation_inputs(self):
        text = ('clang_x64/obj/a.o:\n  input: cxx\n    ../../a.cc\n'
                '    | gen/header.h\n    || gen/order.stamp\n'
                '  validations:\n    gen/check.stamp\n  outputs:\n    generator\n')
        self.assertEqual(query_inputs(text, {'clang_x64/obj/a.o'}),
                         {'../../a.cc', 'gen/header.h', 'gen/order.stamp', 'gen/check.stamp'})
        with self.assertRaisesRegex(RuntimeError, 'Missing'):
            query_inputs('', {'missing'})

    def test_only_safe_host_and_android_object_paths_are_accepted(self):
        for name in ['obj/a.o', 'clang_x64/obj/a.o', 'clang_x64_v8_arm64/obj/v8/a.o']:
            self.assertTrue(object_path(name), name)
        self.assertTrue(host_object('clang_x64/obj/a.o'))
        self.assertFalse(host_object('obj/a.o'))
        self.assertFalse(host_object('clang_arm64/obj/a.o'))
        for name in ['/obj/a.o', '../obj/a.o', 'clang_x64/../obj/a.o',
                     'clang_x64/obj/a.o;echo', 'unknown/obj/a.o', 'obj/a.exe', 'obj/a\\b.o']:
            self.assertFalse(object_path(name), name)

    @unittest.skipUnless(sys.platform == 'linux' and shutil.which('ninja'), 'Real Ninja graph on GitHub')
    def test_dependency_cut_runs_generator_but_leaves_host_compilation_for_workers(self):
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory)
            (out/'input.cc').write_text('fixture')
            (out/'build.ninja').write_text('''rule gen
  command = mkdir -p gen && touch gen/header.h gen/order.stamp
rule cxx
  command = mkdir -p clang_x64/obj && touch $out
build gen/header.h gen/order.stamp: gen
build clang_x64/obj/tool.o: cxx input.cc | gen/header.h || gen/order.stamp
build app: phony clang_x64/obj/tool.o
''')
            def ninja(*args, stdout=None):
                subprocess.run(['ninja', '-C', str(out), *args], check=True, stdout=stdout)
            wrapper, candidates, inputs = write_inputs(ninja, [{'output': 'clang_x64/obj/tool.o'}], out, out)
            self.assertEqual((candidates, inputs), (1, 3))
            ninja('-f', wrapper.name, 'upgrid_host_inputs')
            self.assertTrue((out/'gen/header.h').exists())
            self.assertFalse((out/'clang_x64/obj/tool.o').exists())
            dry = subprocess.check_output(['ninja', '-C', str(out), '-n', 'app'], text=True)
            self.assertIn('[1/1]', dry)
