package org.chromium.chrome.browser.upgrid;

import android.app.Activity;
import org.chromium.ui.base.WindowAndroid;

public final class UpgridOrientationLockTest {
    private static void expect(boolean value) {
        if (!value) throw new AssertionError();
    }

    private static void overlapping(boolean fullscreenFirst, boolean releaseFullscreenFirst) {
        Activity activity = new Activity();
        WindowAndroid window = new WindowAndroid();
        UpgridOrientationLock first = UpgridOrientationLock.acquire(activity, window);
        UpgridOrientationLock second = UpgridOrientationLock.acquire(activity, window);
        UpgridOrientationLock fullscreen = fullscreenFirst ? first : second;
        UpgridOrientationLock player = fullscreenFirst ? second : first;
        activity.setRequestedOrientation(0); // User's rotation button.
        (releaseFullscreenFirst ? fullscreen : player).close();
        expect(window.controlled && activity.getRequestedOrientation() == 0);
        (releaseFullscreenFirst ? player : fullscreen).close();
        expect(!window.controlled && activity.getRequestedOrientation() == -1);
    }

    public static void main(String[] args) {
        // Both entrances and both callback orders must restore the original OS preference.
        overlapping(true, true);
        overlapping(true, false);
        overlapping(false, true);
        overlapping(false, false);

        Activity activity = new Activity();
        WindowAndroid window = new WindowAndroid();
        activity.setRequestedOrientation(1);
        UpgridOrientationLock onlyFullscreen = UpgridOrientationLock.acquire(activity, window);
        expect(activity.getRequestedOrientation() == 14 && window.controlled);
        onlyFullscreen.close();
        onlyFullscreen.close();
        expect(activity.getRequestedOrientation() == 1 && !window.controlled);

        Activity other = new Activity();
        WindowAndroid otherWindow = new WindowAndroid();
        UpgridOrientationLock one = UpgridOrientationLock.acquire(activity, window);
        UpgridOrientationLock two = UpgridOrientationLock.acquire(other, otherWindow);
        one.close();
        expect(!window.controlled && otherWindow.controlled);
        two.close();
        expect(other.getRequestedOrientation() == -1);

        UpgridOrientationLock destroyed = UpgridOrientationLock.acquire(other, otherWindow);
        other.destroyed = true;
        destroyed.close();
        expect(!otherWindow.controlled && other.getRequestedOrientation() == 14);

        System.out.println("7/7 orientation ownership scenarios passed (API stubs, not Android)");
    }
}
