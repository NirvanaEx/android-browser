#!/usr/bin/env python3
"""Read-only Chromium build check. Never change WSL or install dependencies."""
import argparse
import base64
import json
import os
import pathlib
import platform
import shutil
import subprocess
import sys

GIB = 1024 ** 3


def memory_total():
    if sys.platform != "linux":
        return 0
    for line in pathlib.Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemTotal:"):
            return int(line.split()[1]) * 1024
    return 0


def limits(memory, disk, system, min_free_gib=100):
    problems = []
    if system != "Linux":
        problems.append("Android Chromium builds require Linux; use WSL or a Linux build host.")
    # A VM configured for 8 GiB reports slightly less after reserved memory.
    if memory < 7.5 * GIB:
        problems.append("At least an 8 GiB Linux memory allocation is required; limits are not changed automatically.")
    if disk < min_free_gib * GIB:
        problems.append(f"At least {min_free_gib} GiB of free physical host storage is required for this stage.")
    return problems


def inspect(root, min_free_gib=100):
    existing = root.expanduser().resolve()
    while not existing.exists():
        existing = existing.parent
    physical_free = shutil.disk_usage(existing).free
    is_wsl = sys.platform == "linux" and "microsoft" in platform.release().lower()
    storage_verified = not is_wsl
    storage_error = None
    if is_wsl:
        # ext4's advertised free space is the VHD capacity, not host free space.
        # Read the active distro's VHD location from Windows without changing it.
        distro = os.environ.get("WSL_DISTRO_NAME", "").replace("'", "''")
        script = "$distro = '" + distro + "'\n" + r"""
$entry = Get-ItemProperty HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss\* |
  Where-Object DistributionName -EQ $distro | Select-Object -First 1
if (-not $entry) { exit 2 }
$base = [string]$entry.BasePath
if ($base.StartsWith('\\?\')) { $base = $base.Substring(4) }
$drive = [System.IO.Path]::GetPathRoot($base)
([System.IO.DriveInfo]::new($drive)).AvailableFreeSpace
"""
        try:
            encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
            run = subprocess.run(["powershell.exe", "-NoProfile", "-EncodedCommand", encoded],
                                 capture_output=True, text=True, encoding="utf-8", errors="replace",
                                 timeout=20, check=True)
            physical_free = min(physical_free, int(run.stdout.strip()))
            storage_verified = True
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            storage_error = str(error)
    memory = memory_total()
    problems = limits(memory, physical_free, platform.system(), min_free_gib)
    if not storage_verified:
        problems.append("Cannot verify the Windows physical disk behind the WSL VHD; refusing a large checkout.")
    return {"system": platform.system(), "wsl": is_wsl,
            "memoryGiB": round(memory / GIB, 2),
            "physicalFreeGiB": round(physical_free / GIB, 2),
            "storageVerified": storage_verified,
            "storageError": storage_error,
            "checkout": str(root.expanduser().resolve()),
            "ready": not problems, "blockers": problems}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=pathlib.Path,
                        default=pathlib.Path("~/.cache/upgrid/chromium"))
    parser.add_argument("--report", type=pathlib.Path)
    args = parser.parse_args()
    result = inspect(args.checkout)
    serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    print(serialized, end="")
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(serialized, encoding="utf-8")
    return 0 if result["ready"] else 2


if __name__ == "__main__":
    sys.exit(main())
