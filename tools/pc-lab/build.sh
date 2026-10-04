#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
fenix_dir="$script_dir/../fenix"
destination=${1:?Pass a destination APK path}
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$fenix_dir/upstream.json")
commit=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$fenix_dir/upstream.json")
reference="$HOME/.cache/upgrid/firefox-$version"
checkout=${UPGRID_PC_FENIX_DIR:-"$HOME/.cache/upgrid/firefox-pc-$version"}
[[ "$(realpath -m "$checkout")" != "$(realpath -m "$reference")" ]] || {
    echo "PC checkout must be separate from the phone checkout" >&2; exit 1;
}
if [[ ! -e "$checkout" && -d "$reference/.git" ]]; then
    # Copy committed Git objects locally, never the other build's working files.
    git clone --no-hardlinks --no-checkout "$reference" "$checkout"
    git -C "$checkout" checkout --detach "$commit"
fi
export UPGRID_FENIX_DIR="$checkout"
bash "$fenix_dir/prepare.sh"
python3 "$fenix_dir/apply-overlay.py" "$checkout"
host_tools=${UPGRID_HOST_TOOLS_DIR:-"$HOME/.cache/upgrid/host-tools"}
if [[ -d "$host_tools/usr/bin" ]]; then export PATH="$host_tools/usr/bin:$PATH"; fi
export MOZCONFIG="$script_dir/mozconfig"
export GRADLE_USER_HOME=${UPGRID_GRADLE_HOME:-"$HOME/.cache/upgrid/gradle"}
build_jvm_args="-Xmx4g -Xms512m -XX:MaxMetaspaceSize=1g -XX:ActiveProcessorCount=4 -XX:+UseParallelGC"
export GRADLE_FLAGS="--no-daemon -PdisableLeakCanary --max-workers=1 '-Dorg.gradle.jvmargs=$build_jvm_args'"
mkdir -p -- "$GRADLE_USER_HOME"
if [[ ! -e "$GRADLE_USER_HOME/gradle.properties" ]]; then
    cp -- "$fenix_dir/gradle.properties" "$GRADLE_USER_HOME/gradle.properties"
fi
diagnostic_config=${UPGRID_DIAGNOSTICS_CONFIG:-"$script_dir/../../build/fenix/diagnostics-config.json"}
if [[ -f "$diagnostic_config" ]]; then
    export UPGRID_DIAGNOSTICS_ENDPOINT UPGRID_DIAGNOSTICS_TOKEN
    UPGRID_DIAGNOSTICS_ENDPOINT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["endpoint"])' "$diagnostic_config")
    UPGRID_DIAGNOSTICS_TOKEN=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$diagnostic_config")
fi
cd -- "$checkout"
prepared=0
if [[ -e objdir-upgrid-pc/config.status ]]; then prepared=1; fi
# mach reads GRADLE_FLAGS from config.status before consulting the environment.
# Refresh it so an older configuration cannot silently restore larger limits.
./mach configure
if [[ "$prepared" == 1 ]]; then
    ./mach build faster
else
    ./mach build
fi
# Resource flags are supplied once by the freshly generated configuration.
./mach gradle fenix:assembleDebug --console=plain
cp -- objdir-upgrid-pc/gradle/build/mobile/android/fenix/app/outputs/apk/debug/fenix-x86_64-debug.apk "$destination"
