#!/usr/bin/env python3
"""Render the complete overlay against immutable upstream files, without a build."""
import concurrent.futures
import importlib.util
import json
import pathlib
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    spec = importlib.util.spec_from_file_location("upgrid_overlay", ROOT / "apply-overlay.py")
    overlay = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(overlay)
    revision = json.loads((ROOT / "upstream.json").read_text())["commit"]

    def fetch(name):
        url = f"https://raw.githubusercontent.com/chromium/chromium/{revision}/{name}"
        for attempt in range(3):
            try:
                with urllib.request.urlopen(url, timeout=45) as response:
                    return name, response.read().decode("utf-8")
            except (urllib.error.URLError, TimeoutError):
                if attempt == 2:
                    raise
                time.sleep(attempt + 1)

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        originals = dict(pool.map(fetch, overlay.TRACKED))
    rendered = overlay.render(originals)
    receipt = {
        "revision": revision,
        "compiled": False,
        "sourceFiles": len(originals),
        "outputFiles": len(rendered),
        "files": {name: {"before": overlay.sha(originals[name]) if name in originals else None,
                         "after": overlay.sha(content)} for name, content in rendered.items()},
    }
    output = pathlib.Path("build/overlay-check.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(f"Pinned overlay anchors passed: {len(originals)} sources, {len(rendered)} outputs. Not compiled.")


if __name__ == "__main__":
    main()
