#!/usr/bin/env python3
"""Prepare a pinned, independent Chromium checkout; no global system changes."""
import argparse
import json
import os
import pathlib
import subprocess
import sys

from preflight import inspect
from runner import run_checked

HERE = pathlib.Path(__file__).resolve().parent


def run(*args, cwd, env=None):
    run_checked(args, cwd=cwd, env=env)


def checkout(path, remote, revision):
    if not path.exists():
        path.mkdir(parents=True)
        run("git", "init", cwd=path)
        run("git", "remote", "add", "origin", remote, cwd=path)
    elif not (path / ".git").is_dir():
        raise RuntimeError(f"Not an owned Git checkout: {path}")
    actual_remote = subprocess.check_output(
        ["git", "remote", "get-url", "origin"], cwd=path, text=True).strip()
    if actual_remote != remote:
        raise RuntimeError(f"Unexpected remote in {path}; preserving existing checkout")
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=path,
                          capture_output=True, text=True)
    if head.returncode == 0 and head.stdout.strip() == revision:
        # Re-running dependency setup must preserve our already applied overlay.
        return
    dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=path, text=True)
    if dirty:
        raise RuntimeError(f"Local changes in {path}; not resetting them")
    run("git", "fetch", "--depth=1", "origin", revision, cwd=path)
    run("git", "checkout", "--detach", revision, cwd=path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=pathlib.Path,
                        default=pathlib.Path("~/.cache/upgrid/chromium"))
    parser.add_argument("--sync", action="store_true", help="Download Android dependencies after checkout")
    parser.add_argument("--hooks", action="store_true", help="Run official toolchain download hooks after sync")
    args = parser.parse_args()
    root = args.checkout.expanduser().resolve()
    status = inspect(root, min_free_gib=35 if (root / ".upgrid-chromium-owner").exists() else 100)
    if not status["ready"]:
        print(json.dumps(status, indent=2))
        return 2
    pinned = json.loads((HERE / "upstream.json").read_text())
    root.mkdir(parents=True, exist_ok=True)
    marker = root / ".upgrid-chromium-owner"
    if not marker.exists() and any(root.iterdir()):
        raise RuntimeError("Checkout directory is not empty and has no Upgrid ownership marker")
    marker.write_text("upgrid-chromium-v1\n")
    depot = root / "depot_tools"
    checkout(depot, pinned["depotToolsRepository"], pinned["depotToolsCommit"])
    checkout(root / "src", pinned["repository"], pinned["commit"])
    config = ("solutions = [{\"name\": \"src\", \"url\": " + repr(pinned["repository"]) +
              ", \"managed\": False, \"custom_deps\": {}, \"custom_vars\": {}}]\n"
              "target_os = [\"android\"]\n")
    config_path = root / ".gclient"
    if config_path.exists() and config_path.read_text() != config:
        raise RuntimeError("Existing .gclient differs; preserving it")
    config_path.write_text(config)
    env = os.environ.copy()
    env["PATH"] = str(depot) + os.pathsep + env["PATH"]
    env["DEPOT_TOOLS_UPDATE"] = "0"
    env["GCLIENT_PY3"] = "1"
    run(depot / "ensure_bootstrap", cwd=root, env=env)
    if args.sync or args.hooks:
        # src is unmanaged and checkout() already verifies its exact revision.
        run(depot / "gclient", "sync", "--nohooks", "--no-history", "--jobs=1",
            cwd=root, env=env)
    if args.hooks:
        run(depot / "gclient", "runhooks", cwd=root, env=env)
    print(f"Pinned source prepared: {root / 'src'}")
    print("Linux system packages are not installed automatically; see README.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
