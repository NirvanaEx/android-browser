# Startup regression probe

This is a separate instrumentation APK; it is never packaged or distributed with Upgrid. It uses the real application's startup policy, then performs a file existence check and marks a slow call before the first fragment can remove StrictMode's fatal listener. It neither edits nor deletes the browser's profile.

Build with `build.ps1 -DebugKeystore <the same debug keystore used for Fenix>`, then install the output from `build/fenix/startup-probe/startup-probe.apk` on a test emulator. Run:

```powershell
adb -s emulator-5554 shell am instrument -w com.upgrid.startupprobe/com.upgrid.startupprobe.StartupProbe
```

The affected APK terminates with `RuntimeException: StrictMode ThreadPolicy violation` caused by `DiskReadViolation` / `CustomViolation` and opens StartupCrashActivity. The fixed APK must log those diagnostics and return `Startup diagnostics did not terminate the application`. Logcat must include the probe's PASS marker ten seconds after initialization. This controlled test confirms the fatal-policy defect; it does not establish the cause of an individual user's crash without their report.

Then run `tools/tests/startup-smoke.ps1 -ExpectedUrl <a previously saved test URL>` for three ordinary cold launches, each observed for 30 seconds, with the same profile. This test is restricted to emulators, clears only the emulator logcat buffer between attempts, and saves launch output, logs, activities and saved sessions under `build/fenix/startup-smoke`. It does not clear application data.

For optimized, non-debuggable APKs, use `adb root` on the disposable emulator and
pass `-RootedEmulator` to `startup-smoke.ps1`. This reads the saved session directly
instead of using `run-as`, which Android correctly rejects for release builds.

`DiagnosticsProbe` uses reflection into debug-only callable method shapes. R8 may
remove/in-line those entry points (0.4.2 removes `UpgridDiagnostics.get(Context)`),
so that probe's `NoSuchMethodException` is not an ordinary browser-startup failure.
Do not add reflection keep rules to the browser solely for this test. Validate the
optimized native crash path by signalling its observed main PID from the rooted
emulator, restarting, and checking the actual VPS report plus local acknowledgement.
Disable and restore only the emulator's network to check offline queuing/retry.
