#!/usr/bin/env python3
"""Exercise orientation ownership with API stubs; this is not an Android rotation test."""
import argparse
import pathlib
import subprocess
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
STUBS = {
    "android/app/Activity.java": """package android.app;
public class Activity {
    private int orientation = -1;
    public boolean destroyed;
    public int getRequestedOrientation() { return orientation; }
    public void setRequestedOrientation(int value) { orientation = value; }
    public boolean isDestroyed() { return destroyed; }
}
""",
    "android/content/pm/ActivityInfo.java": """package android.content.pm;
public class ActivityInfo { public static final int SCREEN_ORIENTATION_LOCKED = 14; }
""",
    "org/chromium/ui/base/WindowAndroid.java": """package org.chromium.ui.base;
public class WindowAndroid { public boolean controlled; }
""",
    "org/chromium/content_public/browser/ScreenOrientationProvider.java": """package org.chromium.content_public.browser;
import org.chromium.ui.base.WindowAndroid;
public class ScreenOrientationProvider {
    private static final ScreenOrientationProvider INSTANCE = new ScreenOrientationProvider();
    public static ScreenOrientationProvider getInstance() { return INSTANCE; }
    public void setUserControlledOrientation(WindowAndroid window, boolean controlled) {
        window.controlled = controlled;
    }
}
""",
    "org/chromium/build/annotations/NullMarked.java": """package org.chromium.build.annotations;
public @interface NullMarked {}
""",
    "org/chromium/build/annotations/Nullable.java": """package org.chromium.build.annotations;
import java.lang.annotation.*;
@Target({ElementType.TYPE_USE}) public @interface Nullable {}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=pathlib.Path,
                        default=pathlib.Path("~/.cache/upgrid/chromium"))
    args = parser.parse_args()
    jdk = args.checkout.expanduser() / "src/third_party/jdk/current/bin"
    with tempfile.TemporaryDirectory(prefix="upgrid-orientation-test-") as directory:
        output = pathlib.Path(directory)
        sources = []
        for relative, content in STUBS.items():
            path = output / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        subprocess.run([jdk / "javac", "-J-Xmx128m", "--release", "17", "-d", output,
                        *sources, HERE.parent / "overlay/UpgridOrientationLock.java",
                        HERE / "UpgridOrientationLockTest.java"], check=True)
        subprocess.run([jdk / "java", "-Xmx64m", "-cp", output,
                        "org.chromium.chrome.browser.upgrid.UpgridOrientationLockTest"], check=True)


if __name__ == "__main__":
    main()
