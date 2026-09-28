import importlib.util
import pathlib
import sys
import tempfile
import unittest
from unittest import mock
import subprocess

HERE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from preflight import GIB, limits
import runner

spec = importlib.util.spec_from_file_location("overlay", HERE / "apply-overlay.py")
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)


class ResourceTests(unittest.TestCase):
    def test_six_gib_is_not_silently_accepted(self):
        self.assertTrue(limits(6 * GIB, 200 * GIB, "Linux"))

    def test_reserved_vm_memory_is_accepted(self):
        self.assertEqual([], limits(7.76 * GIB, 101 * GIB, "Linux"))

    def test_build_reserve_differs_from_initial_checkout(self):
        self.assertTrue(limits(8 * GIB, 40 * GIB, "Linux"))
        self.assertEqual([], limits(8 * GIB, 40 * GIB, "Linux", min_free_gib=35))
        self.assertTrue(limits(8 * GIB, 19 * GIB, "Linux", min_free_gib=20))

    def test_windows_cannot_build_android_chromium(self):
        self.assertTrue(limits(16 * GIB, 200 * GIB, "Windows"))


class PreservationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        self.originals = {"one.cc": "original one\n", "two.cc": "original two\n"}
        self.desired = {"one.cc": "modified one\n", "two.cc": "modified two\n",
                        "new.cc": "new content\n"}
        for name, text in self.originals.items():
            (self.root / name).write_text(text)

    def test_clean_and_idempotent_application_are_accepted(self):
        overlay.validate_changes(self.root, self.originals, self.desired, {})
        for name, text in self.desired.items():
            (self.root / name).write_text(text)
        overlay.validate_changes(self.root, self.originals, self.desired, {})

    def test_unrelated_change_blocks_entire_write_set(self):
        (self.root / "two.cc").write_text("user work\n")
        before = {path.name: path.read_bytes() for path in self.root.iterdir()}
        with self.assertRaisesRegex(ValueError, "two.cc"):
            overlay.validate_changes(self.root, self.originals, self.desired, {})
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.root.iterdir()})

    def test_new_file_collision_is_preserved(self):
        (self.root / "new.cc").write_text("user file\n")
        with self.assertRaisesRegex(ValueError, "new.cc"):
            overlay.validate_changes(self.root, self.originals, self.desired, {})

    def test_recorded_previous_overlay_can_be_updated(self):
        previous = "prior overlay\n"
        (self.root / "one.cc").write_text(previous)
        receipt = {"one.cc": {"after": overlay.sha(previous)}}
        overlay.validate_changes(self.root, self.originals, self.desired, receipt)
        (self.root / "one.cc").write_text(previous + "user work\n")
        with self.assertRaises(ValueError):
            overlay.validate_changes(self.root, self.originals, self.desired, receipt)

    def test_ambiguous_upstream_anchor_fails_closed(self):
        for value in ("not present", "anchor and anchor"):
            with self.assertRaises(ValueError):
                overlay.replace_once(value, "anchor", "replacement", "file")

    def test_interrupted_overlay_update_can_resume(self):
        prior = "prior overlay\n"
        (self.root / "one.cc").write_text(self.desired["one.cc"])
        (self.root / "two.cc").write_text(prior)
        receipt = {"two.cc": {"after": overlay.sha(self.desired["two.cc"]),
                              "previousAfter": overlay.sha(prior)}}
        overlay.validate_changes(self.root, self.originals, self.desired, receipt)


class ProcessTests(unittest.TestCase):
    def test_low_disk_stops_only_the_owned_process_group(self):
        child = mock.Mock(pid=12345)
        child.wait.side_effect = [subprocess.TimeoutExpired("ninja", 30), -15]
        child.poll.return_value = None
        with mock.patch.object(runner.subprocess, "Popen", return_value=child) as launch, \
             mock.patch.object(runner, "inspect", return_value={
                 "storageVerified": True, "physicalFreeGiB": 19}), \
             mock.patch.object(runner.os, "killpg", create=True) as kill, \
             mock.patch.object(runner.signal, "signal"):
            with self.assertRaisesRegex(RuntimeError, "preserve host disk"):
                runner.run_checked(["ninja", "-j", "1"], cwd=HERE)
            self.assertTrue(launch.call_args.kwargs["start_new_session"])
            kill.assert_called_once_with(12345, runner.signal.SIGTERM)

    def test_failed_launch_restores_signal_handler(self):
        with mock.patch.object(runner.subprocess, "Popen", side_effect=OSError("missing")), \
             mock.patch.object(runner.signal, "signal", return_value="original") as handler:
            with self.assertRaises(OSError):
                runner.run_checked(["missing"], cwd=HERE)
            self.assertEqual(handler.call_args.args, (runner.signal.SIGTERM, "original"))


if __name__ == "__main__":
    unittest.main()
