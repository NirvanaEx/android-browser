# Phone feedback after test publication .9

The user reports three failures in the exact test APK published as Telegram
message 161: Translate is an extension instead of the native mobile Chrome UI;
site fullscreen does not open Upgrid controls (toolbar entry has a short hitch);
the exit icon terminates the app. These are unresolved acceptance failures.

Current source edits are a work in progress, not a verified replacement APK:

- Remove interception of Chrome's Translate command by TWP. Keep the upstream
  native UI and its availability checks. The pinned TranslateManager checks
  HasAPIKeyConfigured; no Google API key secret is configured in GitHub. Native
  service availability needs investigation/configuration, not a fake test key.
- Promote a visible video inside the requesting container during the original
  authorized fullscreen request, after its permission/activation checks. The
  selected video stays in the same document and retains its existing decoder.
  Cross-process ancestor requests are not retargeted. Containers holding only
  a child iframe need separate coverage; universal site compatibility is not
  claimed. Update the Android container-fullscreen assertion to require Upgrid.
- Remove the normal renderer-side exit request from ReleaseUpgridVideo; Android
  FullscreenManager already requests exit. This avoids competing exit requests,
  but is not a proven diagnosis of the phone crash without its stack.

The Android regression scenario now requires that a site's container request
selects the contained video, opens Upgrid controls, preserves decoded frames
and the existing load, and continues playback after the player exit. The Blink
regression also covers selection and fail-closed fallback. These assertions are
source changes only until they pass against the pinned tree and exact APK in
GitHub Actions.

Next: validate overlay against the pinned tree; add/run fullscreen selection,
permission denial and lifecycle regression checks in GitHub; examine exit crash
diagnostics before claiming a fix; update the stale container-keep-site-controls
test and README. Do not silently change it into a passing acceptance claim.
Any new distributed APK needs a higher versionCode than the published 768003112.

No phone is connected to ADB. The user cannot connect it now; do not keep asking.
The VPS collector only has old Fenix 0.6.8 reports, not Chromium .9 crash evidence.
GitHub cache diagnosis run 37167752724 on d519adf reads the exact completed .9 cache
without compilation to recover matching BuildId symbols and the Ninja timing log.
Read its artifact release-diagnostics-RUN before starting any further diagnosis.
The .9 emulator startup failure is separate from the reported phone exit crash.

## Phone feedback and candidate .11 (2026-10-05)

The owner confirms that the installed .10 still misses site-fullscreen takeover.
Exiting fullscreen can either close the browser or leave the page's video unable
to enter fullscreen or Upgrid again. Transitions sometimes stutter. During page
loading a second toolbar briefly appears below the primary one. The supplied
576x1280 screenshot also shows the focused address field at the bottom, and the
owner reports that the keyboard covers it; editing must remain at the top.
The affected site need not be disclosed. Use neutral same-/cross-origin iframe,
container and shadow-root fixtures; do not assume they reproduce the phone crash.

APK identity was checked against the actual files stored by the Telegram relay:
.9 is code 768003112 / SHA c883a7688a020690e2c91a631cf7a6ed721f3b215495fe162af98cbf072bc849;
.10 is code 768003113 / SHA 11d222e1d56de520b540ae555146081c96ed9bfd4050cc95b0110bd60941c15e.
The relay verified .10 as the latest catalogue entry. Java DEX and native `.text`
sections differ, so this is not a renamed .9. The installed phone's version was
not independently read. Translation remains unavailable: removing the TWP menu
interception did not configure Chrome's native translation service.

Candidate source changes:

- Editing takes the normal TOP transition before bottom-omnibox field-trial
  parameters; the dropdown follows the same position supplier. Leaving editing
  retains the browsing preference. A regression runs the actual pinned method,
  reproduces the old behavior, and tests the patched method on GitHub.
- Passive fullscreen attachment now retries renderer-not-ready and temporarily
  non-interactable states within a two-second deadline. Exit, tab replacement
  and destruction cancel retries. No activation or fullscreen permission is added.
- Browser/site fullscreen exit immediately releases the matching player session
  without requesting another browser exit. Previously it waited for polling.
- A valid passive fullscreen video no longer waits for unrelated frames before
  its controls attach. Manual selection still ranks all discovery candidates.
- Android scenarios require repeated site-button entry/exit across direct,
  clipped, shadow and iframe pages, plus focused address bounds with the IME open.

These are candidate changes, not verified phone fixes. The duplicate toolbar,
the crash stack and universal site compatibility remain unresolved. Native
translation is not implemented by this candidate. Use a new .11 identity for
any compiled APK; do not replace or republish .10. The earlier test-publication
authorization applied to .10, not an automatic waiver for future failed builds.
