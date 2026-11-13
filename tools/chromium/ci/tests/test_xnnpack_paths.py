"""Regression for 190 XNNPACK objects excluded from run 37156601112."""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import pipeline
from wave_worker import write_graph

NAME = 'obj/third_party/xnnpack/f16_arch=armv8.2-a+fp16/kernel.o'


class XnnpackPathsTests(unittest.TestCase):
    def test_pending_native_routes_equal_path_to_native_wave(self):
        action = dict(output=NAME, command="../../clang -c input.c -o '" + NAME + "'")
        with tempfile.TemporaryDirectory() as directory:
            state = pathlib.Path(directory)
            (state / 'toolchain.ninja').write_text('rule cc\n')

            def ninja(*args, stdout):
                if args[:2] == ('-n', '-v'):
                    stdout.write('[1/1] ' + action['command'] + '\n')
                else:
                    self.assertEqual(args, ('-t', 'compdb', 'cc'))
                    json.dump([action], stdout)

            with patch.object(pipeline, 'STATE', state), patch.object(pipeline, 'OUT', state), \
                    patch.object(pipeline, 'ninja', side_effect=ninja):
                self.assertEqual(pipeline.pending_native(), [action])
                self.assertEqual(pipeline.pending_native('host'), [])

    @unittest.skipUnless(sys.platform == 'linux' and shutil.which('ninja'), 'Real Ninja on GitHub')
    def test_worker_graph_and_depfile_accept_equal_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory)
            (out / 'input.h').write_text('fixture')
            # Exercise the real worker graph and Ninja depfile grammar without
            # compiling Chromium. A changed input must still invalidate output.
            command = f"touch '{NAME}' && printf '%s\\n' '{NAME}: input.h' > '{NAME}.d'"
            write_graph(out, [dict(output=NAME, command=command)])
            subprocess.run(['ninja', '-C', directory, '-f', 'wave.ninja'], check=True, capture_output=True)
            self.assertTrue((out / NAME).is_file())
            dry = ['ninja', '-C', directory, '-f', 'wave.ninja', '-n']
            self.assertIn('no work to do', subprocess.check_output(dry, text=True))
            (out / 'input.h').unlink()
            self.assertIn(NAME, subprocess.check_output(dry, text=True))


if __name__ == '__main__':
    unittest.main()
