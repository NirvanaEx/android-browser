#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
bash "$script_dir/prepare.sh"
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$script_dir/upstream.json")
checkout=${UPGRID_FENIX_DIR:-"$HOME/.cache/upgrid/firefox-$version"}
case "${1:-baseline}" in
    baseline)
        if [[ -e "$checkout/.upgrid-overlay.json" ]]; then
            echo "This checkout contains the Upgrid overlay; use 'build.sh upgrid'." >&2
            exit 1
        fi
        ;;
    upgrid|optimized) python3 "$script_dir/apply-overlay.py" "$checkout" ;;
    *) echo "Usage: $0 [baseline|upgrid|optimized]" >&2; exit 2 ;;
esac
host_tools=${UPGRID_HOST_TOOLS_DIR:-"$HOME/.cache/upgrid/host-tools"}
if [[ -d "$host_tools/usr/bin" ]]; then
    export PATH="$host_tools/usr/bin:$PATH"
fi
export MOZCONFIG="$script_dir/mozconfig"
# Keep the user's 16 GB workstation responsive, including for full R8 builds.
# These limits intentionally apply over older saved Gradle properties. Do not
# increase them automatically after an OOM or a slow optimization pass.
build_jvm_args="-Xmx4g -Xms512m -XX:MaxMetaspaceSize=1g -XX:ActiveProcessorCount=4 -XX:+UseParallelGC"
export GRADLE_FLAGS="${GRADLE_FLAGS:-} -PdisableLeakCanary --max-workers=1 '-Dorg.gradle.jvmargs=$build_jvm_args'"
export GRADLE_USER_HOME=${UPGRID_GRADLE_HOME:-"$HOME/.cache/upgrid/gradle"}
diagnostic_config=${UPGRID_DIAGNOSTICS_CONFIG:-"$script_dir/../../build/fenix/diagnostics-config.json"}
if [[ -f "$diagnostic_config" ]]; then
    export UPGRID_DIAGNOSTICS_ENDPOINT
    export UPGRID_DIAGNOSTICS_TOKEN
    UPGRID_DIAGNOSTICS_ENDPOINT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["endpoint"])' "$diagnostic_config")
    UPGRID_DIAGNOSTICS_TOKEN=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$diagnostic_config")
fi
mkdir -p -- "$GRADLE_USER_HOME"
if [[ ! -e "$GRADLE_USER_HOME/gradle.properties" ]]; then
    cp -- "$script_dir/gradle.properties" "$GRADLE_USER_HOME/gradle.properties"
fi
cd -- "$checkout"
if [[ "${1:-baseline}" != baseline ]]; then
    ./mach build faster
else
    ./mach build
fi
# mach already prepends GRADLE_FLAGS. Passing --max-workers again is rejected
# by Gradle 9.7, even when the two values are identical.
gradle_args=()
if [[ "${UPGRID_BUILD_VERBOSE:-0}" == 1 ]]; then
    gradle_args+=(--info)
fi
build_task=fenix:assembleDebug
if [[ "${1:-baseline}" == optimized ]]; then
    build_task=fenix:assembleRelease
fi
./mach gradle "$build_task" --console=plain "${gradle_args[@]}"
