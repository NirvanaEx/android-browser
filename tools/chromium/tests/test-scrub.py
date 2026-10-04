#!/usr/bin/env python3
"""Compile and run the actual scrub state with Chromium's JDK; no Android claims."""
import argparse
import pathlib
import subprocess
import tempfile

HERE = pathlib.Path(__file__).resolve().parent

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=pathlib.Path,
                        default=pathlib.Path("~/.cache/upgrid/chromium"))
    parser.add_argument("--jdk", type=pathlib.Path, help="JDK root on the GitHub test runner")
    args = parser.parse_args()
    jdk = args.jdk / "bin" if args.jdk else args.checkout.expanduser() / "src/third_party/jdk/current/bin"
    with tempfile.TemporaryDirectory(prefix="upgrid-scrub-test-") as output:
        subprocess.run([jdk / "javac", "-J-Xmx128m", "--release", "17", "-d", output,
                        HERE.parent / "overlay/UpgridScrubSession.java",
                        HERE / "UpgridScrubSessionTest.java"], check=True)
        subprocess.run([jdk / "java", "-Xmx64m", "-cp", output,
                        "org.chromium.chrome.browser.upgrid.UpgridScrubSessionTest"], check=True)

if __name__ == "__main__":
    main()
