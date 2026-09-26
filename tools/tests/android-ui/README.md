# Android player touch regression

Build `build.ps1` with JDK 17 and Android SDK 35. It produces
`build/fenix/android-ui/snapshot.jar`, containing the shell-only `Snapshot`,
`Touch` and `SetText` accessibility helpers.
It never becomes an APK dependency or runs in the browser process. It reads the
active window without requiring the UI to become idle: Media3 updates its time
labels frequently, which can make the stock `uiautomator dump` time out.

`Snapshot <output.xml> [settleMilliseconds]` accepts an optional delay from 0 to
2000 milliseconds, defaulting to 0. It waits after connecting UiAutomation and
enabling view IDs, before reading the first window root. For Gecko HTML snapshots,
use 1200–2000 ms when the native toolbar is available before the page's virtual
accessibility children. This bounded delay does not wait for the page to become idle.

After pushing the rebuilt helper to the emulator, for example:

```powershell
adb -s emulator-5560 shell 'CLASSPATH=/data/local/tmp/upgrid-snapshot.jar app_process /system/bin com.upgrid.uitest.Snapshot /data/local/tmp/upgrid-ui.xml 1500'
```

`SetText <exact-resource-id> <value>` replaces the text in the already focused,
editable field using `ACTION_SET_TEXT`. Pass the value as one quoted shell
argument. It does not change focus or press Enter. This avoids dropped synthetic
key events when entering fixture URLs on an emulator using ARM translation.
The helper waits up to 5 seconds to find the exact field and another 5 seconds
for its refreshed text to match, then prints `SET_TEXT_OK length=...` without
echoing the value. Failure prints `SET_TEXT_FAILED: <class>: <reason>` and exits
with code 1 after cleanup, without an uncaught AndroidRuntime crash. Tree searches
are bounded, and UiAutomation disconnects on both success and failure.

After focusing the address editor, for example:

```powershell
adb -s emulator-5560 shell 'CLASSPATH=/data/local/tmp/upgrid-snapshot.jar app_process /system/bin com.upgrid.uitest.SetText ADDRESSBAR_SEARCH_BOX "http://127.0.0.1:8766/aspect.html?probe=1"'
```

On the disposable test emulator, open the fixture served by
`tools/tests/player-server.cjs` at `http://127.0.0.1:8766/native.html` (ADB reverse
TCP 8766). Press **Pause at 10 seconds**, then the browser's player action. Run:

```powershell
./tools/tests/android-ui/build.ps1
./tools/tests/player-tap-seek.ps1
```

The test operates only on an emulator. It checks individual and rapid repeated
taps, start/end boundaries, preservation of pause, swipe/hold rejection,
separate Media3 buttons and time-bar scrubbing. XML snapshots and the result
file are written under `build/fenix/tap-seek-regression/`.
This is a touch-dispatch regression, not evidence of network-site compatibility
or a performance measurement on a physical device.
