"""Regression checks for device isolation and failed/stale APK protection."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

spec = importlib.util.spec_from_file_location("lab", Path(__file__).with_name("lab.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)


class WorkbenchTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.data_patch = patch.object(lab, "DATA", self.root)
        self.data_patch.start()
        self.addCleanup(self.temporary.cleanup)
        self.addCleanup(self.data_patch.stop)
        self.workbench = lab.Lab()

    @unittest.skipUnless(lab.os.name == "nt", "Windows file locking")
    def test_live_operation_lock_waits_and_is_released_after_exit(self):
        with lab.exclusive_operation():
            with self.assertRaises(lab.LabBusy):
                with lab.exclusive_operation():
                    self.fail("Second operation acquired an active lock")
        with lab.exclusive_operation():
            pass

    def test_wsl_windows_error_is_readable(self):
        message = "Ошибка соединения Wsl/Service/0x8007274c"
        self.assertEqual(lab.decode_output(message.encode("utf-16-le")), message)

    @unittest.skipUnless(lab.os.name == "nt", "Windows file locking")
    def test_build_queue_does_not_block_existing_device_controls(self):
        with lab.exclusive_operation():
            with lab.exclusive_operation("device"):
                with self.assertRaises(lab.LabBusy):
                    with lab.exclusive_operation("device"):
                        self.fail("Device mutation was not serialized")

    def test_other_avd_on_reserved_port_is_never_controlled(self):
        self.workbench.connected = Mock(return_value=True)
        self.workbench.adb = Mock(return_value="Personal_Phone\nOK")
        self.workbench.shell = Mock()
        with self.assertRaisesRegex(RuntimeError, "left untouched"):
            self.workbench.open_page("native")
        self.workbench.shell.assert_not_called()

    def test_active_release_blocks_emulator_start(self):
        self.workbench.connected = Mock(return_value=False)
        self.workbench.active_builds = Mock(return_value=[{"pid": 42, "task": "./mach gradle fenix:assembleRelease"}])
        with patch.object(lab.subprocess, "Popen") as spawn:
            with self.assertRaisesRegex(RuntimeError, "left untouched"):
                self.workbench.start()
            spawn.assert_not_called()

    def test_any_active_build_blocks_overlay_mutation(self):
        self.workbench.active_builds = Mock(return_value=[{"pid": 42, "task": "./mach gradle fenix:assembleDebug"}])
        with patch.object(lab.subprocess, "Popen") as spawn:
            with self.assertRaises(RuntimeError):
                self.workbench.build()
            spawn.assert_not_called()

    def test_wrong_abi_split_is_rejected(self):
        apk = self.root / "bad.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"dummy")
        with self.assertRaisesRegex(RuntimeError, "lacks Gecko"):
            lab.validate_apk(apk)

    def test_correct_gecko_abi_is_accepted(self):
        apk = self.root / "good.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libxul.so", b"dummy")
        lab.validate_apk(apk)

    def test_native_pc_gecko_is_accepted(self):
        apk = self.root / "native.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/x86_64/libxul.so", b"dummy")
        self.assertEqual(lab.validate_apk(apk), ["x86_64"])

    def test_fingerprint_tracks_content_and_deleted_inputs_but_not_reports(self):
        app = self.root / "app/src/main"
        app.mkdir(parents=True)
        source = app / "Example.kt"
        source.write_text("before")
        with patch.object(lab, "ROOT", self.root):
            before = lab.fingerprint()
            (self.root / "report.txt").write_text("new report")
            self.assertEqual(before, lab.fingerprint())
            source.write_text("after")
            self.assertNotEqual(before, lab.fingerprint())
            changed = lab.fingerprint()
            source.unlink()
            self.assertNotEqual(changed, lab.fingerprint())

    def test_android_shell_arguments_cannot_add_commands(self):
        self.workbench.adb = Mock(return_value="")
        self.workbench.shell("am", "start", "-d", "https://example.org/?a=1&b=';reboot")
        command = self.workbench.adb.call_args.args[1]
        self.assertEqual(lab.shlex.split(command), ["am", "start", "-d", "https://example.org/?a=1&b=';reboot"])

    def test_page_uses_android_intent_receiver_not_home_activity(self):
        self.workbench.require_device = Mock()
        self.workbench.fixtures = Mock()
        self.workbench.shell = Mock(return_value="Starting: Intent")
        self.workbench.open_page("lab")
        args = self.workbench.shell.call_args.args
        self.assertNotIn("-n", args)
        self.assertEqual(args[-2:], ("-p", lab.PACKAGE))
        self.assertIn("http://127.0.0.1:8766/lab.html", args)

    def test_snapshot_preserves_inputs_when_live_source_changes(self):
        app = self.root / "app/src/main"
        app.mkdir(parents=True)
        source = app / "Example.kt"
        source.write_text("before")
        with patch.object(lab, "ROOT", self.root):
            snapshot, saved_hash = lab.snapshot_sources()
            source.write_text("after")
            self.assertEqual((snapshot / "app/src/main/Example.kt").read_text(), "before")
            self.assertEqual(lab.fingerprint(snapshot), saved_hash)
            self.assertNotEqual(lab.fingerprint(), saved_hash)

    def test_snapshot_tampering_does_not_replace_last_good_apk(self):
        (self.root / "tools/fenix").mkdir(parents=True)
        (self.root / "tools/fenix/upstream.json").write_text('{"version":"155.0.1"}')
        good = self.root / "current.apk"
        good.write_bytes(b"last good build")
        self.workbench.require_idle = Mock()
        self.workbench.wsl = Mock(return_value="/repo")
        process = Mock(returncode=0)
        process.poll.return_value = 0
        with patch.object(lab, "ROOT", self.root), patch.object(lab, "snapshot_sources", return_value=(self.root, "before")), \
                patch.object(lab, "fingerprint", return_value="after"), patch.object(lab, "run", return_value="githead"), \
                patch.object(lab.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(RuntimeError, "snapshot changed"):
                self.workbench.build()
        self.assertEqual(good.read_bytes(), b"last good build")


if __name__ == "__main__":
    unittest.main()
