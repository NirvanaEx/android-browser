import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "fenix_overlay", Path(__file__).resolve().parents[1] / "fenix/apply-overlay.py",
)
overlay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(overlay)


class OverlayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="upgrid-overlay-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        fixtures = {
            "src/main/java/org/mozilla/fenix/components/Components.kt": "    val useCases by lazyMonitored {\n",
            "src/main/java/org/mozilla/fenix/browser/BaseBrowserFragment.kt": "        initializeUI(view)\n",
            "build.gradle": 'applicationId "org.mozilla"\napplicationIdSuffix ".fenix.debug"\n',
            "src/debug/AndroidManifest.xml":
                '<application tools:replace="android:name" android:name="org.mozilla.fenix.FenixApplication" />\n',
        }
        for relative, text in fixtures.items():
            path = self.root / overlay.APP / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        application = self.root / overlay.APP / "src/main/java/org/mozilla/fenix/FenixApplication.kt"
        application.write_text('''                onUpdatePermissionRequest = components.addonUpdater::onUpdatePermissionRequest,
            )
        } catch (e: UnsupportedOperationException) {
            logger.error("Failed to initialize web extension support", e)
''', encoding="utf-8")
        support = self.root / overlay.SUPPORT / "src/main/java/mozilla/components/support/webextensions/WebExtensionSupport.kt"
        support.parent.mkdir(parents=True, exist_ok=True)
        support.write_text('''        onExtensionsLoaded: ((List<WebExtension>) -> Unit)? = null,
!installedExtensions.containsKey(extension.id) && !extension.isBuiltIn()
                    onConfirm: (PermissionPromptResponse) -> Unit,
                ) {
                    store.dispatch(
                    this@WebExtensionSupport.onUpdatePermissionRequest?.invoke(
                    onPermissionsGranted: ((Boolean) -> Unit),
                ) {
                    store.dispatch(
''', encoding="utf-8")
        self.git("add", ".")
        self.git("-c", "user.name=Overlay Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "fixture")
        commit = self.git("rev-parse", "HEAD").strip()
        self.pin = patch.dict(overlay.UPSTREAM, {"commit": commit})
        self.pin.start()
        self.addCleanup(self.pin.stop)
        generated = {
            f"{overlay.APP}/src/main/java/org/mozilla/fenix/upgrid/VideoPlayerBridge.kt": b"generated bridge\n",
            f"{overlay.APP}/src/main/java/org/mozilla/fenix/components/Components.kt": b"modified components\n",
        }
        generator = patch.object(overlay, "generate", return_value=generated)
        generator.start()
        self.addCleanup(generator.stop)

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True)

    def run_overlay(self, *args):
        with patch.object(sys, "argv", ["overlay", str(self.root), *args]), contextlib.redirect_stdout(io.StringIO()):
            overlay.main()

    def test_reapply_and_verify(self):
        self.run_overlay()
        manifest = (self.root / ".upgrid-overlay.json").read_bytes()
        self.run_overlay()
        self.run_overlay("--check")
        self.assertEqual((self.root / ".upgrid-overlay.json").read_bytes(), manifest)

    def test_manual_edits_are_not_overwritten(self):
        self.run_overlay()
        target = self.root / overlay.APP / "src/main/java/org/mozilla/fenix/upgrid/VideoPlayerBridge.kt"
        target.write_text("manual edits\n", encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "Local edits preserved"):
            self.run_overlay()
        self.assertEqual(target.read_text(), "manual edits\n")

    def test_dry_run_does_not_mutate_checkout(self):
        self.run_overlay("--dry-run")
        self.assertFalse((self.root / ".upgrid-overlay.json").exists())
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_wrong_upstream_is_rejected_before_writing(self):
        with patch.dict(overlay.UPSTREAM, {"commit": "0" * 40}):
            with self.assertRaisesRegex(SystemExit, "Wrong upstream"):
                self.run_overlay()
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_body_replacement_preserves_adjacent_code_and_quoted_braces(self):
        source = 'fun target() { val text = "{"; list.forEach { call() } }\nfun next() { keep() }'
        changed = overlay.replace_body(source, 'fun target()', '    replacement()')
        self.assertEqual(changed, 'fun target() {\n    replacement()\n}\nfun next() { keep() }')


if __name__ == "__main__":
    unittest.main()
