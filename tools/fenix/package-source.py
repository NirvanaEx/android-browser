"""Package source and notices accompanying a locally built Upgrid APK."""
import argparse
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("checkout", type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
checkout = args.checkout.resolve()
pin = json.loads((repo / "tools/fenix/upstream.json").read_text())
manifest = json.loads((checkout / ".upgrid-overlay.json").read_text())
output = repo / "build/fenix/upgrid-next-modified-source.zip"
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    for name in manifest:
        source = (checkout / name).resolve()
        if not source.is_relative_to(checkout):
            raise ValueError("Overlay path is outside checkout")
        archive.write(source, "firefox/" + name)
    for name in ("LICENSE", "toolkit/content/license.html"):
        if (checkout / name).is_file():
            archive.write(checkout / name, "firefox/" + name)
    for directory in ("tools/fenix", "tools/tests", "tools/diagnostics", "app/src/main", "docs"):
        for path in (repo / directory).rglob("*"):
            if path.is_file() and "__pycache__" not in path.parts:
                archive.write(path, "upgrid/" + path.relative_to(repo).as_posix())
    archive.writestr("README.txt",
        "Upgrid Next: modified Mozilla source files and reproducible overlay.\n"
        "Upstream: https://github.com/mozilla-firefox/firefox\n"
        f'Commit: {pin["commit"]}\n'
        "Get the full pinned source using upgrid/tools/fenix/prepare.sh.\n"
        "The firefox/ directory contains every modified/generated file; copy over that checkout.\n"
        "Build instructions and license notices are included. No signing keys or account data included.\n")
print(output)
