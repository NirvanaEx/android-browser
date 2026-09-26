# Startup regression probe

This is a separate instrumentation APK; it is never packaged or distributed with Upgrid. The `StartupProbe` entry point uses the real application's startup policy, then performs a file existence check and marks a slow call before the first fragment can remove StrictMode's fatal listener. That entry point neither edits nor deletes the browser's profile. The separate `DiagnosticsProbe` modes below have their own effects; `browser_empty` deliberately closes disposable test tabs.

Build with `build.ps1 -DebugKeystore <the same debug keystore used for Fenix>`, then install the output from `build/fenix/startup-probe/startup-probe.apk` on a test emulator. Run:

```powershell
adb -s emulator-5554 shell am instrument -w com.upgrid.startupprobe/com.upgrid.startupprobe.StartupProbe
```

The affected APK terminates with `RuntimeException: StrictMode ThreadPolicy violation` caused by `DiskReadViolation` / `CustomViolation` and opens StartupCrashActivity. The fixed APK must log those diagnostics and return `Startup diagnostics did not terminate the application`. Logcat must include the probe's PASS marker ten seconds after initialization. This controlled test confirms the fatal-policy defect; it does not establish the cause of an individual user's crash without their report.

Then run `tools/tests/startup-smoke.ps1 -ExpectedUrl <a previously saved test URL>` for three ordinary cold launches, each observed for 30 seconds, with the same profile. This test is restricted to emulators, clears only the emulator logcat buffer between attempts, and saves launch output, logs, activities and saved sessions under `build/fenix/startup-smoke`. It does not clear application data.

For optimized, non-debuggable APKs, use `adb root` on the disposable emulator and
pass `-RootedEmulator` to `startup-smoke.ps1`. This reads the saved session directly
instead of using `run-as`, which Android correctly rejects for release builds.

If `uiautomator dump` cannot obtain an idle UI while a page is animating, build
`tools/tests/android-ui/build.ps1` and pass `-UseSnapshotProbe`. This uses the
existing accessibility Snapshot helper; PID, saved-tab, menu and session-storage
assertions are unchanged. A snapshot proves the inspected UI, not frame latency.

`DiagnosticsProbe -e mode browser_empty` exercises BrowserFragment initialization
with no selected tab on a **disposable emulator containing only test tabs**. This
mode intentionally closes all its ordinary and private tabs using the existing
`TabsUseCases.removeAllTabs(false)`; it does not clear application data, settings,
cookies or the rest of the profile. Do not use a profile containing tabs to retain.

The probe waits for session restoration, stops the existing `HomepageTabBinding`
through the pinned debug class's private lazy getter, and checks `tabs=0` and
`selectedTabId=null` before opening BrowserFragment. A temporary attach/lifecycle
observer records the new BrowserFragment at `ON_START`, with a non-null view,
empty store and `browserInitialized=false`: its `onViewCreated` has completed the
tab-null initialization path. The observation is required immediately after the
navigation transaction executes. Five seconds later the probe requires the same
empty state and a resumed BrowserFragment or HomeFragment. The latter is an
intentional upstream redirect in `BaseBrowserFragment.observeRestoreComplete`,
which pops home after restoration if there are no tabs/selection. The PASS marker
records both the observed initialization and the final screen. Finally the probe
removes its temporary observers and restarts the homepage binding, so
normal behavior can create a fresh home tab without changing preferences. A home
launch without this setup now creates an automatic `about:home` tab and cannot
test the empty browser path.

```powershell
& tools/tests/startup-probe/build.ps1 -DebugKeystore '\\wsl.localhost\Ubuntu\home\neyron\.android\debug.keystore'
adb -s emulator-5554 install -r build/fenix/startup-probe/startup-probe.apk
adb -s emulator-5554 shell am instrument -w -e mode browser_empty com.upgrid.startupprobe/com.upgrid.startupprobe.DiagnosticsProbe
```

Use the matching debug browser APK and signing key. After this check, open a real
page, enter/exit the player, and cold-start with that saved tab. The empty-path
check deliberately removes test tabs and must precede persistence checks.

`DiagnosticsProbe -e mode tab_memory` samples the real debug BrowserStore every
five seconds for six minutes while the test driver opens fixture tabs. It reports
only aggregate tab, linked-session, form, media, saved-state and idle counts. Use `fixtures/tabs.html`
to check a draft, history and scroll restoration; linked-session counts are not RAM
measurements. The probe does not change the production memory policy.

Use `tabs.html?id=100&plain=1` for ordinary pages without form controls or
sessionStorage writes. Gecko can conservatively report form data even for blank
textarea fixtures. The ordinary fixture and the draft fixture test separate cases.

`DiagnosticsProbe` uses reflection into debug-only callable method shapes. R8 may
remove/in-line those entry points (0.4.2 removes `UpgridDiagnostics.get(Context)`),
so that probe's `NoSuchMethodException` is not an ordinary browser-startup failure.
Do not add reflection keep rules to the browser solely for this test. Validate the
optimized native crash path by signalling its observed main PID from the rooted
emulator, restarting, and checking the actual VPS report plus local acknowledgement.
Disable and restore only the emulator's network to check offline queuing/retry.

`DiagnosticsProbe -e mode ui_io` (debug APK only) constructs a fresh diagnostic
collector and calls 100 breadcrumbs plus a handled error on Android's main thread
under a fatal disk-read/write StrictMode policy. Its throwable also fails if stack
traversal happens on that thread. It then flushes from the instrumentation thread;
verify the `ui_io_probe` acknowledgement on the VPS. This is a deterministic check
for forbidden UI work, not a benchmark of device responsiveness.
