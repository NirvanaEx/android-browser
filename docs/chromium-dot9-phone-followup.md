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
