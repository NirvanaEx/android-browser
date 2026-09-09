# Android player touch regression

Build `build.ps1` with JDK 17 and Android SDK 35. It produces
`build/fenix/android-ui/snapshot.jar`, a shell-only accessibility snapshot helper.
It never becomes an APK dependency or runs in the browser process. It reads the
active window without requiring the UI to become idle: Media3 updates its time
labels frequently, which can make the stock `uiautomator dump` time out.

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
