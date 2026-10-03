# Upgrid .9 test coverage

This document describes implemented checks, not passed Android acceptance.
The exact signed .9 APK is still building. The .8 baseline fails in the native
ARM64 translator of the GitHub emulator (API 30, 35 and 36).

All builds, compilation and Android execution run on GitHub-hosted runners.
Local Python harness tests are lightweight checks of the test tooling only.

| Area | Implemented checks | Remaining acceptance |
| --- | --- | --- |
| APK | Package/version/signature/hash/ABI/ZIP integrity and size report | Read actual .9 outputs and compare size with 606255302-byte .8 |
| Update | Install baseline, store data, update without clearing, restore saved tab | Real passing execution on compatible ARM64 Android |
| Startup | Cold saved-tab launch; close page targets, cold empty launch, native menu/new-tab action | Execute; also verify tab-tray gestures and regular startup visually |
| Player | Decoded frames and changing screenshot pixels, play/pause, entry/exit, repeated cycles, clipped parent, closed shadow root, same/cross-origin iframe | Execute; audio, DRM, hardware decode and real-site content remain separate |
| Lifecycle | Native manual rotation, unchanged orientation on entry, Home/return without auto-play, tab activation/pause | Execute; tab selection uses DevTools, so native tray UI is not claimed tested |
| Menu | Three native open/close cycles, History/New tab visibility, empty startup/new tab | All menu actions, downloads, bookmarks and settings are not exhaustively automated |
| Stability | App/renderer native crashes, Java crashes and ANR detection in logcat, screenshots on failure | Sustained real-device use, memory/performance and codec checks |
| Tampermonkey foundation | Real-manager fixture results, legacy/async GM storage, style injection, local GM request, iframe, reload/restart counters, early raw page-world fetch hook | Normal Web Store install/permissions and script install must actually succeed; menu invocation still required |
| Actual user scripts | Versioned inventory of VK theater, VK preview, Neyron updates and Kick chat sync; download SHA and JS syntax checks in cloud | Install actual scripts through Tampermonkey and test on their real sites; syntax is not execution |
| Translation | Russian text in current page, unchanged URL, reload/cold restart, translate=no and form-input preservation | Normal TWP setup; verify Google provider, site/language exceptions, dynamic text, original text, offline retry |
| Adblock | Default uBO Lite installation code exists | Enabled registry, network/cosmetic blocking, disable/enable and site/incognito exceptions need Android evidence |

The imported Tampermonkey fixtures are the existing project fixtures. They are
served only on the ephemeral runner's loopback port through ADB reverse. They
do not install themselves, grant permissions, mock GM APIs or inject the user
scripts through DevTools. Missing extension/script installation causes a failed
fixture check, never a substitute pass.

The user scripts were found in the previously documented read-only snapshots
under the old chromium-banana checkout's build/chromium/features-20260930.
That checkout was not modified. The committed inventory records reviewed versions
and hashes; downloads that change require review and are reported explicitly.

`results.json` includes individual scenarios and `acceptanceCoverage` for every
required release check. Missing scenarios are `blocked`, failures remain `failed`,
and partial smoke-test success cannot make overall acceptance pass. Provider
identity and actual user-script execution are deliberately not inferred from
translated DOM text or fixture API success. `distributionApproved` remains false.

No test suite can prove the absence of all bugs. Publication additionally requires
the approved Chromium security base and a complete exact-APK acceptance receipt.
