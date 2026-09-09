#!/usr/bin/env bash
# Run in Linux / WSL. Keep the large Mozilla checkout out of the app repository.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
host_tools=${UPGRID_HOST_TOOLS_DIR:-"$HOME/.cache/upgrid/host-tools"}
if [[ -d "$host_tools/usr/bin" ]]; then
    export PATH="$host_tools/usr/bin:$PATH"
fi
mapfile -t upstream < <(python3 - "$script_dir/upstream.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
for key in ("repository", "tag", "commit", "version"):
    print(data[key])
PY
)
[[ ${#upstream[@]} == 4 ]] || { echo "Invalid upstream.json" >&2; exit 1; }
checkout=${UPGRID_FENIX_DIR:-"$HOME/.cache/upgrid/firefox-${upstream[3]}"}
if [[ ! -e "$checkout" ]]; then
    mkdir -p -- "$(dirname -- "$checkout")"
    git clone --depth 1 --single-branch --branch "${upstream[1]}" "${upstream[0]}" "$checkout"
fi
[[ -d "$checkout/.git" ]] || { echo "Not a Mozilla checkout: $checkout" >&2; exit 1; }
actual=$(git -C "$checkout" rev-parse HEAD)
[[ "$actual" == "${upstream[2]}" ]] || {
    echo "Expected ${upstream[2]}, found $actual. Existing checkout left untouched." >&2
    exit 1
}
echo "Verified Firefox ${upstream[3]} at $checkout"

case "${1:-prepare}" in
    prepare) ;;
    bootstrap)
        cd -- "$checkout"
        # Installs Mozilla-managed build tools in the user's home, without sudo.
        ./mach --no-interactive bootstrap \
            --application-choice mobile_android_artifact_mode --no-system-changes
        ./mach python python/mozboot/mozboot/android.py --artifact-mode --no-interactive
        ;;
    *) echo "Usage: $0 [prepare|bootstrap]" >&2; exit 2 ;;
esac
