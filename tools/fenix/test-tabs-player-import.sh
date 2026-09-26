#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
checkout=${UPGRID_FENIX_DIR:-"$HOME/.cache/upgrid/firefox-155.0.1"}
python3 "$script_dir/apply-overlay.py" "$checkout" --check
export MOZCONFIG="$script_dir/mozconfig"
export GRADLE_USER_HOME=${UPGRID_GRADLE_HOME:-"$HOME/.cache/upgrid/gradle"}
export PATH="$HOME/.cache/upgrid/host-tools/usr/bin:$PATH"
cd -- "$checkout"
./mach gradle :fenix:testDebugUnitTest --console=plain -PdisableLeakCanary --max-workers=1 \
  --tests '*upgrid.UpgridTabMemoryTest' --tests '*upgrid.UpgridTabsTest' \
  --tests '*upgrid.UpgridHomeRoutingTest' --tests '*AboutHomeBindingTest' --tests '*AppRequestInterceptorTest' \
  --tests '*bindings.HomepageTabBindingTest' \
  --tests '*tabstray.controller.DefaultTabManagerControllerTest' \
  --tests '*upgrid.UpgridPlayerOrientationTest'
./mach gradle :components:lib-bookmarks-file:testDebugUnitTest \
  :components:lib-bookmark-parser-jsoup:testDebugUnitTest --console=plain --max-workers=1
