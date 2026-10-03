# APK size and player isolation — 2026-10-03

The inspected APK is `146.0.7680.31-upgrid.8-player-fixes-test`, code
`768003111`, SHA-256
`058403f2646440fb2507101beaeba87d8a8470c523561d231b9df494ac942fd2`.
This matches the saved installation receipt. No device was connected during
this investigation; the current installation was not read from a phone.

## Measured APK size

The signed file occupies **606,255,302 bytes** (606.3 MB / 578.2 MiB).
The [ZIP inventory](validation/apk-size-768003111.json) was read without
unpacking, repacking or modifying the APK.

| Contents | Stored bytes, rounded to decimal MB |
| --- | ---: |
| Native libraries, ARM64 only | 398.5 |
| Assets | 98.5 |
| Nine DEX files | 69.6 |
| Android resource table | 36.2 |
| Other resources and ZIP/signature overhead | 3.5 |

`libchrome.so` alone is 397.0 MB. There are no bundled x86 or ARM32 engines.
The build uses `is_debug=true` and `is_java_debug=true`; symbol levels are
already zero. Removing debug symbols again is therefore not the primary fix.

The next candidate uses the existing optimized extension configuration:
`is_debug=false`, `is_java_debug=false`, with ARM64, extensions and codecs
preserved. Chromium enables Java shrinking when Java debug is disabled
([pinned configuration](https://raw.githubusercontent.com/chromium/chromium/4d3225104176d18430bb7bef9f67394686316c2f/build/config/android/config.gni)).
ThinLTO and official PGO remain off. The `extensions-ci` profile uses the
CI-owned output directory and refuses local invocation. The pipeline now
honors `release.json.profile` instead of always forcing `extensions-dev`.

The reduction has **not yet been measured**: it requires a successful APK
build. The pipeline saves `apk-size.json` with the candidate for comparison.
Stored native libraries/DEX are not simply re-zipped: that would change the
signed APK and its Android loading/alignment assumptions.

## Player defect and changed behavior

The renderer previously accepted fullscreen of any ancestor containing the
selected video. It hid site siblings with persistent-video ancestor flags,
then put a transparent Android control window above that container. This does
not establish an isolated video surface: transformed/clipped ancestors and
shadow trees are not covered by the light-DOM fullscreen CSS selector.
See [the pinned UA stylesheet](https://raw.githubusercontent.com/chromium/chromium/4d3225104176d18430bb7bef9f67394686316c2f/third_party/blink/renderer/core/css/fullscreen.css).
These are code-level failure paths consistent with the report, not a recorded
reproduction on the user's sites.

The candidate requires fullscreen of the selected video itself before showing
Upgrid controls. Explicit player entry requests that video even if a site
container is already fullscreen. It keeps the video/decoder/source in place,
preserves play/pause and uses the existing native-click activation path.
Page parents and their styles are not rewritten.

Automatic adoption now applies only to actual video fullscreen. A website's
container fullscreen keeps its own controls; Upgrid does not overlay them.
To use Upgrid in that case, return to the page and use the browser's player
button. This is an intentional compatibility tradeoff pending Android tests.
The legacy container-presentation helper and its UA stylesheet override have
been removed; the overlay restores the original stylesheet in cached sources.

## Build and validation

The prior [cloud build](https://github.com/NirvanaEx/android-browser/actions/runs/36870154608)
failed because the shard snapshot omitted `.hpp11`, included `.c` files and
extensionless Eigen headers. The snapshot collector now includes these inputs;
a regression test covers the four reported missing include paths.

Local results: 19/19 tooling tests, 4/4 portable CI tests (seven Linux-only
checks skipped locally), 7/7 Chromium YouTube adapter tests, and 101/101 legacy
Fenix DOM tests. The latter do not exercise the changed Chromium renderer.
The overlay rendered successfully against all 32 pinned upstream source files
(56 outputs); the original fullscreen stylesheet is restored exactly.
Local checks are limited to Python/Node tests, source rendering and APK
metadata. No GN/Ninja, C++, Java or APK compilation ran on this PC.
Blink regression tests were updated for direct video, container and shadow
fullscreen, but require execution in a Chromium test build.

Android acceptance remains required: a real video frame, repeated entry/exit,
paused/playing continuity, direct video, transformed container, shadow root,
iframe, late site controls, source replacement, orientation, Back, background,
tab switching and saved-tab cold start. The fixture
`tools/tests/fixtures/chromium-fullscreen.html` includes separate container and
video fullscreen actions plus a transformed/clipped container case. Its layout
diagnostics do not claim that a box behind the top layer is visibly painted.

Candidate version: `146.0.7680.31-upgrid.9-isolated-player-test` / `768003112`.
No new APK is accepted or published by this source change.

GitHub validation passed on source commit `1a7e54d`:
[run 37133490760](https://github.com/NirvanaEx/android-browser/actions/runs/37133490760).
All 11 CI tests, 19 tooling tests, seven adapter tests and two Ninja cache tests
passed on the GitHub runner. This was validation mode; native compilation and
APK assembly were skipped. The unrelated legacy Android workflow was cancelled
before delivery and excluded for this Chromium feature branch.

## Cloud Android baseline — 2026-10-03

Run `37136340535` installed the exact .8 baseline APK in the API 30 x86_64
Google APIs emulator (advertising arm64-v8a support). The app failed to start:
logcat records SIGILL in `libndk_translation.so`, specifically
`DecodeSimdThreeDifferent`, followed by process death. The launch timeout is
a consequence of that crash, not evidence of player failure. No player or
update-preservation check passed in that run. Its artifact
`android-test-evidence-37136340535` includes results.json, device.json, logcat,
failure UI and screenshot. The next probe uses the API 35 image with the same
signed APK to test newer native translation; success remains unverified.

The previous unbounded probe `37134446720` required forced cancellation after
ordinary cancellation did not complete. The harness now bounds ADB/DevTools
calls and retains failure evidence when a diagnostic command also fails.
