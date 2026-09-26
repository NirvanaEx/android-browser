#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
checkout=${UPGRID_FENIX_DIR:-"$HOME/.cache/upgrid/firefox-155.0.1"}
python3 "$script_dir/apply-overlay.py" "$checkout" --check
export MOZCONFIG="$script_dir/mozconfig"
export GRADLE_USER_HOME=${UPGRID_GRADLE_HOME:-"$HOME/.cache/upgrid/gradle"}
export PATH="$HOME/.cache/upgrid/host-tools/usr/bin:$PATH"
cd -- "$checkout"
./mach build faster
test_args=(--tests '*upgrid.UpgridHomeRoutingTest' --tests '*AboutHomeBindingTest'
  --tests '*AppRequestInterceptorTest' --tests '*FenixBrowserUseCasesTest'
  --tests '*BrowserToolbarSearchMiddlewareTest' --tests '*FenixSearchMiddlewareTest'
  --tests '*TranslationsBindingTest')
if (( $# )); then test_args=("$@"); fi
./mach gradle :fenix:testDebugUnitTest --console=plain -PdisableLeakCanary --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx4g -Xms512m -XX:MaxMetaspaceSize=1g -XX:ActiveProcessorCount=4 -XX:+UseParallelGC' \
  "${test_args[@]}"
