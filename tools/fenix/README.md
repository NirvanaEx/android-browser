# Fenix migration workbench

For interactive testing on the Windows PC, open `Upgrid-PC.cmd` at the repository
root. The [PC lab](../pc-lab/README.md) provides an isolated Android window,
incremental build/install, source watch, local test pages and bug capture.

This builds the Firefox Android source pinned in `upstream.json`. It is a migration prototype, not the finished replacement browser. The existing `app/` remains independently buildable.

## Linux / WSL setup

Run from the Upgrid repository in Ubuntu/WSL:

```bash
bash tools/fenix/host-tools.sh # optional if unzip, zip, make and m4 are missing
bash tools/fenix/prepare.sh bootstrap
bash tools/fenix/build.sh baseline
```

Bootstrap downloads Mozilla's build tools, JDK and Android SDK into the user directory. It uses artifact mode, with precompiled native Gecko libraries. The first checkout and build require several gigabytes of downloads and substantial disk space. The source checkout defaults to `~/.cache/upgrid/firefox-155.0.1`; Gradle defaults to `~/.cache/upgrid/gradle`. A separate Gradle configuration limits workers and memory for WSL.

`UPGRID_FENIX_DIR`, `UPGRID_GRADLE_HOME` and `UPGRID_HOST_TOOLS_DIR` override those locations. Existing Gradle properties are preserved. The SDK location follows `MOZBUILD_STATE_PATH` or `~/.mozbuild`. The scripts currently target Linux and arm64 Android.

## Upgrid player prototype

After verifying the unmodified baseline:

```bash
python3 tools/fenix/apply-overlay.py ~/.cache/upgrid/firefox-155.0.1 --dry-run
bash tools/fenix/build.sh upgrid
python3 tools/fenix/apply-overlay.py ~/.cache/upgrid/firefox-155.0.1 --check
```

The overlay reuses the player JavaScript, Kotlin bridge, controls and resources from `app/`. It adds a small Fenix lifecycle adapter and copies resources with an `upgrid_` prefix. Hooks connect it to Fenix's existing component container and browser view. It creates no additional engine or runtime.

The prototype package is `com.upgrid.browser.next.debug`, separate from the old app and Firefox. It has a compact Upgrid home/menu, bookmark at the address field's right edge, player and manual translation actions in the toolbar, and Fenix's top tab strip on displays with a smallest width of at least 600dp. Desktop mode defaults off, including tablets; existing explicit choices remain. Page loads do not open translation offers. Since 0.6.2, player mode first uses the selected video's real Gecko fullscreen with Upgrid controls, retaining its decoder, buffer and source. Some cases need one additional page tap for fullscreen activation. Media3 is a bounded fallback when fullscreen cannot be used. Since 0.6.5, ordinary entry and return preserve playback, including a manual pause; backgrounding or switching tabs still pauses. See [player design](../../docs/native-player.md) for limits. The menu includes an AdBlock switch and the standard extension manager. Telemetry is disabled before Glean initialization. HTML bookmark import is available from the bookmarks menu; see [import limits](../../docs/browser-import.md). Unsigned extension installation remains unverified.

`UpgridAdblock` installs uBlock Origin 1.74.0 from the pinned AMO URL at first launch, after Fenix registers its extension delegate. It uses AddonManager so installation participates in the existing store and update machinery. The small WebExtensionSupport patch accepts an explicit set of automatically granted IDs; only uBO is passed in this build. Other extensions retain normal install, optional-permission and update prompts. Existing disabled uBO is left disabled. No XPI is bundled; network/install failures require a later launch to retry. The AMO listing was checked on 2026-09-07: https://addons.mozilla.org/api/v5/addons/addon/ublock-origin/.

Generated files live in the external checkout. `apply-overlay.py` checks the pinned commit and refuses to overwrite unknown local edits. Its manifest records hashes so repeat application can update its own generated files. The script does not reset or delete a checkout. A baseline build refuses an already overlaid checkout.

APK output: `objdir-upgrid/gradle/build/mobile/android/fenix/app/outputs/apk/debug/` inside the Firefox checkout. **Use only `fenix-arm64-v8a-debug.apk` with this mozconfig**: the fetched native Gecko artifact is ARM64 even if Gradle also emits other ABI APKs. Build logs should be redirected to its `artifacts/` directory so failures can be inspected without repeating a full build.

## Validation

### Optimized candidate

`bash tools/fenix/build.sh optimized` builds `fenix:assembleRelease` with upstream R8 code
optimization and resource shrinking, without Android debugging, debug Compose tooling,
LeakCanary or the debug-channel StrictMode policy. `upgrid` still builds the debug variant
for instrumentation. The release overlay uses the existing `com.upgrid.browser.next.debug`
package, local debug signing key and `release.json` version; it does not inherit Firefox's
shared UID or Firefox version numbering. Check the final APK's package, signer and version
before installing it as an upgrade. Release output is in `outputs/apk/release/`; only use ARM64.
Keep the matching `outputs/mapping/release/` files with each candidate for crash analysis.
The automatic VPS crash collector remains enabled when configured.
All build modes use a 4 GB JVM heap, at most 1 GB metaspace and one Gradle worker,
including the optimized R8 pass. The wrapper supplies these limits even when an
older saved Gradle configuration allows more memory or workers. The former
6 GB R8 heap and `UPGRID_OPTIMIZED_HEAP` override belong to an earlier experiment;
they are no longer used on the user's 16 GB workstation. A slow build or an OOM
must not automatically increase the limits.

After Kotlin compilation or unit tests, stop the idle Gradle daemon belonging to
the Upgrid Gradle home before starting the optimized build. In the 0.6.6 check,
the reused daemon exhausted the 4 GB heap in repeated full GC; a fresh daemon
reused the compiled outputs and completed R8 with the same limits. Do not run
the emulator alongside this optimization pass.

The agreed WSL profile is 6 GB RAM, 4 CPU and 8 GB swap. Apply global WSL changes
and restart it only at a safe boundary after active builds finish. Start the
Android emulator with 2048 MB RAM, 2 cores and `BelowNormal` process priority;
leave an already running emulator alone until its next launch.

The optimized build is a performance candidate, not evidence of an improvement on a physical
device. Compare identical pages, tab counts and thermal conditions. A translating emulator
cannot establish phone rendering speed. No Gecko cache/displayport/process-limit tuning is
applied: increasing pre-rendered content can make the observed low-memory pressure worse.

For a private-site-free comparison, serve `http://127.0.0.1:8766/media-scroll.html` with
the existing fixture server and ADB reverse. It generates 48 unique 960×540 JPEG posters
locally and waits for initial decoding. Compare text, posters, and posters plus one manually
started video, scrolling down and back after “Готово”. Watch whether text disappears too.
This deliberately memory-heavy fixture separates remote image downloads from rendering;
it is not a timing benchmark and does not prove compatibility with a particular website.

```bash
node --test tools/tests/player.test.cjs
python3 tools/tests/fenix_overlay_test.py
bash tools/fenix/test.sh # after building the Upgrid overlay
bash tools/fenix/test-ui-response.sh # diagnostics queue and menu regression tests
bash tools/fenix/test-player-continuity.sh # toolbar, desktop default and manual translation
bash tools/fenix/test-player-seamless.sh # manual orientation and blocked site rotation while playing
bash tools/fenix/test-translation-navigation.sh # first URL/search, lifecycle and translation UI regressions
```

The Node tests cover discovery without fullscreen, page/style restoration, nested-frame acknowledgement/timeouts, cancellation, playing-video selection, shadow roots and frame/request isolation using a simulated DOM and extension API. The Python tests check function replacement, repeat application and preservation of local edits. Kotlin tests cover the uBO permission boundary alongside existing WebExtensionSupport tests. See `VALIDATION.md` for actual device/emulator checks and limitations.

For translation batching, run `node --test tools/tests/translation-batch.test.cjs`
with `UPGRID_TRANSLATION_WORKER` pointing at the generated
`toolkit/components/translations/content/translations-engine.worker.js`.
This checks the real worker classes with a fake inference backend; validate the
actual en→ru model and the packaged `assets/omni.ja` separately. See
[translation design and fixture](../../docs/page-translation.md).

Local player fixtures are in `tools/tests/fixtures/`. Generate `build/fenix/sample.mp4` (any short synthetic H.264/AAC clip), run `node tools/tests/player-server.cjs`, then `adb reverse tcp:8766 tcp:8766`. Open `http://127.0.0.1:8766/player.html` in the app. The linked cases exercise clipped/transformed ancestors, a cross-origin iframe and competing videos. The server binds only to loopback and serves a fixed file whitelist.

`controls.html` reproduces website controls with explicitly visible children, recurring style changes and newly created panels. `controls.html?shadow=1` runs the same case inside an open shadow root. With the server running and Playwright/Firefox installed, run `node --test tools/tests/player-browser.test.cjs`. Set `UPGRID_PLAYWRIGHT_MODULE` to an absolute module path if Playwright is installed outside the repository. `UPGRID_PLAYER_SOURCE` optionally selects an older script to verify that the regression test fails against it. These tests use real Firefox CSS/media/MutationObserver behavior and a mock extension message channel; Android integration is checked separately.

Since 0.5.0, Fenix transfers supported network sources to a separate Media3 video window.
It does not resize or hide website elements. The CSS isolation tests above still cover
the legacy app; native handoff tests cover the new path, including unchanged DOM,
pause/position return, stale requests, and source identity checks. Since 0.6.0,
bound player APIs supply alternative MP4/HLS/DASH sources. Untransferable media
and native playback failures can use real Gecko video fullscreen with Upgrid
controls; it may require one direct click on “Открыть плеер”. This retains the
original media session without cropping or resizing site containers. See
[native player design and limits](../../docs/native-player.md). `native.html` is the
Android fixture for the new screen. A first video frame must be observed on Android;
passing DOM tests or seeing controls alone is insufficient.

## Startup safety for shared builds

The upstream debug variant enables a fatal StrictMode listener during application startup. Disk reads or slow-call diagnostics, including vendor framework calls, can therefore terminate the app before the first screen. The Upgrid overlay starts StrictMode with `withPenaltyDeath = false`: detection and logging remain enabled, and real uncaught exceptions still go through Fenix's crash reporter. Do not re-enable the fatal policy in APKs shared for ordinary use.

Session restoration can create `BrowserFragment` while its selected tab is still absent. `initializeUI(view)` then deliberately leaves the toolbar uninitialized. Attach `UpgridPlayerFeature` only inside the tab-present initialization path, after the toolbar and engine UI exist. Its initial hidden state and stopping an inactive player must not call the browser's collapse callback. The stop still cancels pending takeover requests. `DiagnosticsProbe` mode `browser_empty` enters the real browser destination with no current tab; version 0.4.0-diag1 crashes there with the same stack received from the user's device.

`MenuNavigation` belongs in `MenuFrame.header`, outside its scrollable content. Putting it inside that content nests two vertical scroll containers and crashes Compose measurement on browser-page menus; the home menu has no navigation row and cannot reproduce it. Use `startup-smoke.ps1 -CheckMenu -BrowserTabTitle 'Player controls regression' -ExpectedUrl 'http://127.0.0.1:8766/controls.html'` with the saved fixture tab to exercise the browser-page menu on each launch.

On a slow ARM64-translating emulator, `-AllowSlowEmulatorLaunch` allows the short `am start -W` first-frame deadline to expire, then still requires a stable process for 30 seconds, resumed browser UI, no crash dialog, the saved tab and actual menu controls. The warning and original launch result are retained. This checks functional survival and must not be reported as a startup-speed pass.

`tools/tests/startup-probe/` is a separate instrumentation APK that checks this against the real startup path. It reproduces the old failure and verifies that the fixed app survives the same diagnostic. `tools/tests/startup-smoke.ps1` then checks repeated ordinary cold starts, the foreground UI, absence of crash dialogs and preservation of a saved test tab. Install upgrades with `adb install -r`; do not clear the profile between checks. After deliberately crashing the old APK, dismiss its existing report prompt with Close before checking for new startup failures.

The complete upstream source and its license notices remain in the pinned checkout. Before distributing a modified Fenix build, package the required source/license material and finish Upgrid branding. The build scripts do not publish releases.

## Diagnostic builds

`release.json` controls the Upgrid version name and monotonically increasing version code. `0.4.0-diag1` adds the user-requested automatic diagnostic queue and private VPS receiver. Configure, deploy, inspect and test it using [the diagnostics guide](../diagnostics/README.md). Endpoint and write-only credential come from ignored build configuration, never from committed source. The test probe remains a separate APK. `startup-smoke.ps1 -CheckMenu` verifies opening the right menu on each cold start.

Run `python3 tools/fenix/package-source.py /path/to/firefox-checkout` to package the generated modifications, overlay, diagnostics receiver, tests and license notices into `build/fenix/upgrid-next-modified-source.zip`; private build configuration and signing keys are excluded.
