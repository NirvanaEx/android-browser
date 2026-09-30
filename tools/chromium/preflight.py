#!/usr/bin/env python3
"""Read-only Chromium build check. Never change WSL or install dependencies."""
import argparse
import base64
import json
import os
import pathlib
import platform
import re
import shutil
import subprocess
import sys

GIB = 1024 ** 3
_wsl_host_drive = None


def mounted_host_free(drive, mounts):
    """Read fresh host free space, only through the matching Windows mount."""
    def unescape(value):
        return re.sub(r"\\([0-7]{3})", lambda match: chr(int(match[1], 8)), value)

    if not re.fullmatch(r"[A-Za-z]:\\", drive):
        raise ValueError("Unsupported Windows volume for the WSL disk")
    for line in mounts.splitlines():
        fields = line.split()
        if len(fields) < 3:
            continue
        source, target, filesystem = fields[:3]
        if filesystem in ("9p", "drvfs") and unescape(source).casefold() == drive.casefold():
            return shutil.disk_usage(unescape(target)).free
    raise ValueError("The Windows volume containing the WSL disk is not mounted")


def wsl_host_free():
    # Discover the VHD's backing volume before compilation. Repeatedly starting
    # Windows PowerShell under build load can time out. Cache only the volume
    # identity, never the amount of free space; validate its mount on every call.
    global _wsl_host_drive
    if _wsl_host_drive is None:
        distro = os.environ.get("WSL_DISTRO_NAME", "").replace("'", "''")
        script = "$distro = '" + distro + "'\n" + r"""
$entry = Get-ItemProperty HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss\* |
  Where-Object DistributionName -EQ $distro | Select-Object -First 1
if (-not $entry) { exit 2 }
$base = [string]$entry.BasePath
if ($base.StartsWith('\\?\')) { $base = $base.Substring(4) }
[System.IO.Path]::GetPathRoot($base)
"""
        encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
        run = subprocess.run(["powershell.exe", "-NoProfile", "-EncodedCommand", encoded],
                             capture_output=True, text=True, encoding="utf-8", errors="replace",
                             timeout=20, check=True)
        _wsl_host_drive = run.stdout.strip()
    return mounted_host_free(_wsl_host_drive, pathlib.Path("/proc/mounts").read_text())


def memory_total(field="MemTotal"):
    if sys.platform != "linux":
        return 0
    for line in pathlib.Path("/proc/meminfo").read_text().splitlines():
        if line.startswith(field + ":"):
            return int(line.split()[1]) * 1024
    return 0


def limits(memory, disk, system, min_free_gib=100, *, low_memory=False, swap=0):
    problems = []
    if system != "Linux":
        problems.append("Android Chromium builds require Linux; use WSL or a Linux build host.")
    # VMs report slightly less memory after reservations. The constrained mode
    # is explicit, requires swap, and is restricted to one worker in build.py.
    if low_memory and (memory < 5.5 * GIB or swap < 7.5 * GIB):
        problems.append("Constrained builds require 6 GiB RAM and 8 GiB swap; limits are not changed automatically.")
    elif not low_memory and memory < 7.5 * GIB:
        problems.append("At least an 8 GiB Linux memory allocation is required; limits are not changed automatically.")
    if disk < min_free_gib * GIB:
        problems.append(f"At least {min_free_gib} GiB of free physical host storage is required for this stage.")
    return problems


def inspect(root, min_free_gib=100, *, low_memory=False):
    existing = root.expanduser().resolve()
    while not existing.exists():
        existing = existing.parent
    physical_free = shutil.disk_usage(existing).free
    is_wsl = sys.platform == "linux" and "microsoft" in platform.release().lower()
    storage_verified = not is_wsl
    storage_error = None
    if is_wsl:
        # ext4's advertised free space is the VHD capacity, not host free space.
        try:
            physical_free = min(physical_free, wsl_host_free())
            storage_verified = True
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            storage_error = str(error)
    memory = memory_total()
    swap = memory_total("SwapTotal")
    problems = limits(memory, physical_free, platform.system(), min_free_gib,
                      low_memory=low_memory, swap=swap)
    if not storage_verified:
        problems.append("Cannot verify the Windows physical disk behind the WSL VHD; refusing a large checkout.")
    return {"system": platform.system(), "wsl": is_wsl,
            "memoryGiB": round(memory / GIB, 2),
            "swapGiB": round(swap / GIB, 2), "lowMemoryMode": low_memory,
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
