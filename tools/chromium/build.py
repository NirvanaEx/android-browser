#!/usr/bin/env python3
"""Build Upgrid from pinned Chromium without changing WSL limits."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys

from preflight import inspect
from runner import run_checked

HERE = pathlib.Path(__file__).resolve().parent
PROFILES = {
    "player": ("Upgrid", "args.gn", "upgrid"),
    "extensions": ("UpgridExtensions", "args-extensions.gn", "upgrid-extensions"),
    "extensions-ci": ("Upgrid", "args-extensions.gn", "upgrid-extensions-ci"),
    "extensions-dev": ("Upgrid", "args-extensions-dev.gn", "upgrid-extensions-dev"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=pathlib.Path,
                        default=pathlib.Path("~/.cache/upgrid/chromium"))
    parser.add_argument("--generate-only", action="store_true")
    parser.add_argument("--check-integration", action="store_true",
                        help="Compile changed browser/Blink objects and check Chromium Android Java")
    parser.add_argument("--target", default="chrome_public_apk")
    parser.add_argument("--profile", choices=PROFILES, default="player",
                        help="extensions-dev reuses the existing Upgrid build; extensions uses a separate optimized build")
    parser.add_argument("--jobs", type=int, default=1,
                        help="Concurrent Ninja jobs (default: 1; increase for available RAM/CPU)")
    parser.add_argument("--low-memory", action="store_true",
                        help="Use the agreed 6 GiB RAM / 8 GiB swap profile with one worker; never change WSL limits")
    args = parser.parse_args()
    if args.profile == "extensions-ci" and os.environ.get("GITHUB_ACTIONS") != "true":
        parser.error("extensions-ci is reserved for a disposable GitHub Actions workspace")
    if args.jobs < 1:
        parser.error("--jobs must be at least 1")
    if args.low_memory and args.jobs != 1:
        parser.error("--low-memory requires exactly one worker")
    root = args.checkout.expanduser().resolve()
    status = inspect(root, min_free_gib=35, low_memory=args.low_memory)
    if not status["ready"]:
        print(json.dumps(status, indent=2))
        return 2
    if not (root / ".upgrid-chromium-owner").exists():
        raise RuntimeError("Not an Upgrid checkout; run prepare.py first")
    src, depot = root / "src", root / "depot_tools"
    subprocess.run([sys.executable, HERE / "apply-overlay.py", src], check=True)
    overlay = json.loads((root / "upgrid-overlay-receipt.json").read_text())
    output_name, config, receipt_prefix = PROFILES[args.profile]
    output = src / "out" / output_name
    desired_args = (HERE / config).read_text()
    if output.exists() and not (output / ".upgrid-build-owner").exists():
        raise RuntimeError("Unowned output directory; preserving it")
    output.mkdir(parents=True, exist_ok=True)
    (output / ".upgrid-build-owner").write_text("upgrid-chromium-v1\n")
    args_path = output / "args.gn"
    if args_path.exists() and args_path.read_text() != desired_args:
        raise RuntimeError("Existing GN args differ; review them before regenerating")
    if not args_path.exists():
        args_path.write_text(desired_args)
    env = os.environ.copy()
    env["PATH"] = str(depot) + os.pathsep + env["PATH"]
    env["DEPOT_TOOLS_UPDATE"] = "0"
    env["GCLIENT_PY3"] = "1"
    run_checked([depot / "gn", "gen", output, "--fail-on-unused-args"], cwd=src, env=env)
    if args.generate_only:
        return 0
    targets = [args.target]
    if args.check_integration:
        targets = [
            "obj/chrome/browser/browser/upgrid_player.o",
            "obj/third_party/blink/renderer/core/core/local_frame_mojo_handler.o",
            "obj/third_party/blink/renderer/core/core/html_media_element.o",
            "obj/third_party/blink/renderer/modules/media_controls/media_controls/media_controls_orientation_lock_delegate.o",
            "obj/third_party/blink/renderer/modules/remoteplayback/remoteplayback/remote_playback.o",
            "obj/chrome/android/chrome_java.javac.jar",
            "obj/chrome/android/chrome_java__errorprone.stamp",
            "obj/chrome/android/chrome_public_apk__lint/build.lint.stamp",
        ]
        if args.profile.startswith("extensions"):
            targets += [
                "obj/chrome/browser/extensions/extensions/external_provider_impl.o",
                "obj/chrome/browser/ui/ui/extension_install_dialog_view_android.o",
            ]
    observer = None
    final_state = os.environ.get('UPGRID_FINAL_AUDIT_DIR')
    if final_state:
        if args.target != 'chrome_public_apk' or args.check_integration:
            raise RuntimeError('Final distributed audit requires the APK target')
        from ci.final_progress import prepare
        observer = prepare(src / 'third_party/ninja/ninja', output, final_state)
        env['NINJA_STATUS'] = '[%f/%t] '
    run_checked([depot / "autoninja", "-C", output, "-j", str(args.jobs), *targets],
                cwd=src, env=env, observer=observer)
    # A successful command must be tied to the inputs that were actually built.
    current_revision = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=src, text=True).strip()
    if current_revision != overlay["revision"] or args_path.read_text() != desired_args:
        raise RuntimeError("Revision or GN args changed during compilation; no receipt written")
    for name, expected in overlay["files"].items():
        actual = hashlib.sha256((src / name).read_bytes()).hexdigest()
        if actual != expected["after"]:
            raise RuntimeError(f"Source changed during compilation: {name}; no receipt written")
    inputs = {"revision": overlay["revision"],
              "overlay": {name: item["after"] for name, item in overlay["files"].items()},
              "argsSha256": hashlib.sha256(desired_args.encode()).hexdigest()}
    input_digest = hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()
    verification = {"inputs": inputs, "inputDigest": input_digest, "targets": targets,
                    "buildProfile": args.profile,
                    "resourcePreflight": status,
                    "jobs": args.jobs,
                    "finishedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    "androidPlaybackVerified": False, "bananaParityVerified": False,
                    "distributionApproved": False}
    if args.check_integration:
        (root / f"{receipt_prefix}-integration-receipt.json").write_text(
            json.dumps({**verification, "compiled": True}, indent=2) + "\n")
    apk = output / "apks" / "ChromePublic.apk"
    if not args.check_integration and args.target == "chrome_public_apk":
        if not apk.is_file():
            raise RuntimeError("Build returned success but ChromePublic.apk is missing")
        with apk.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        receipt = {**verification, "apk": str(apk), "sha256": digest, "compiled": True}
        (root / f"{receipt_prefix}-build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
