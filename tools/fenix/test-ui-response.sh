#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$script_dir/upstream.json")
checkout=${UPGRID_FENIX_DIR:-"$HOME/.cache/upgrid/firefox-$version"}
python3 "$script_dir/apply-overlay.py" "$checkout" --check
export MOZCONFIG="$script_dir/mozconfig"
export GRADLE_USER_HOME=${UPGRID_GRADLE_HOME:-"$HOME/.cache/upgrid/gradle"}
host_tools=${UPGRID_HOST_TOOLS_DIR:-"$HOME/.cache/upgrid/host-tools"}
if [[ -d "$host_tools/usr/bin" ]]; then
    export PATH="$host_tools/usr/bin:$PATH"
fi
cd -- "$checkout"
./mach gradle :fenix:testDebugUnitTest --console=plain -PdisableLeakCanary --max-workers=1 \
    --tests '*UpgridDiagnosticsTest' --tests '*UpgridMenuTest' --tests '*MenuDialogMiddlewareTest'
