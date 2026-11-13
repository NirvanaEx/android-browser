package org.chromium.chrome.browser.upgrid;

import android.app.Activity;
import android.os.Handler;
import org.chromium.chrome.browser.fullscreen.FullscreenManager;
import org.chromium.chrome.browser.fullscreen.FullscreenOptions;
import org.chromium.chrome.browser.tab.Tab;

/** Tests browser/renderer readiness separately; not a real Android playback test. */
public final class UpgridFullscreenAttachTest {
    static final class Fixture {
        final Activity activity = new Activity();
        final FullscreenManager manager = new FullscreenManager();
        final Tab tab = new Tab();
        Tab selected = tab;
        final UpgridPlayerCoordinator coordinator;
        Fixture() {
            Handler.reset();
            UpgridPlayer.reset();
            coordinator = new UpgridPlayerCoordinator(activity, manager, () -> selected);
        }
        void enter() {
            manager.active = true;
            tab.contents.fullscreen = true;
            coordinator.onEnterFullscreen(tab, new FullscreenOptions());
        }
        void exit() {
            manager.active = false;
            tab.contents.fullscreen = false;
            coordinator.onExitFullscreen(tab);
        }
    }
    static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Fixture f = new Fixture();
        f.enter();
        f.tab.interactable = false;
        Handler.advance(100);
        expect(UpgridPlayer.calls == 0, "must wait for interactable tab");
        f.tab.interactable = true;
        f.tab.contents.fullscreen = false;
        Handler.advance(100);
        expect(UpgridPlayer.calls == 0, "must wait for browser fullscreen");
        f.tab.contents.fullscreen = true;
        UpgridPlayer.failures = 1;
        Handler.advance(50);
        expect(UpgridPlayer.calls == 1 && !UpgridPlayer.active, "renderer still not ready");
        Handler.advance(50);
        expect(UpgridPlayer.calls == 2 && UpgridPlayer.active, "retry after renderer acknowledgement");
        Handler.advance(500);
        expect(UpgridPlayer.calls == 2, "do not duplicate attached player");
        f.exit();
        expect(UpgridPlayer.exits == 1 && !UpgridPlayer.active, "release immediately on site exit");
        f.enter();
        Handler.advance(1);
        expect(UpgridPlayer.calls == 3 && UpgridPlayer.active, "immediate repeated entry");

        f = new Fixture();
        UpgridPlayer.failures = 1000;
        f.enter();
        Handler.advance(2500);
        int calls = UpgridPlayer.calls;
        expect(calls > 1 && calls <= 40, "retry budget");
        Handler.advance(10000);
        expect(UpgridPlayer.calls == calls, "stop after deadline");

        f = new Fixture();
        UpgridPlayer.hold = true;
        f.enter();
        Handler.advance(0);
        f.exit();
        UpgridPlayer.pending.onResult(false);
        Handler.advance(2000);
        expect(UpgridPlayer.calls == 1, "late failure must not reopen after exit");

        f = new Fixture();
        UpgridPlayer.failures = 100;
        f.enter();
        Handler.advance(0);
        f.selected = new Tab();
        Handler.advance(2000);
        expect(UpgridPlayer.calls == 1, "do not follow a changed tab");

        f = new Fixture();
        UpgridPlayer.failures = 100;
        f.enter();
        Handler.advance(0);
        f.coordinator.destroy();
        Handler.advance(2000);
        expect(UpgridPlayer.calls == 1, "destroy cancels retries");
        System.out.println("Fullscreen readiness, retry deadline, exit/re-entry and cancellation passed.");
    }
}
