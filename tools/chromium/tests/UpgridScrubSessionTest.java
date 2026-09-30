package org.chromium.chrome.browser.upgrid;

/** Host-JVM checks for playback intent; these do not simulate Android touch dispatch or video. */
public final class UpgridScrubSessionTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        playingAndPaused();
        newerDragOwnsResume();
        explicitPauseWins();
        backgroundWins();
        backRestoresIntent();
        cancellationDoesNotSeek();
        previewIsBounded();
        duplicateReleaseIsIgnored();
        System.out.println("PASS: 8 scrub lifecycle scenarios (host JVM, no Android playback)");
    }

    private static void playingAndPaused() {
        for (boolean playing : new boolean[] {false, true}) {
            UpgridScrubSession s = new UpgridScrubSession();
            check(s.begin(playing, 12, 100), "start finite video");
            check(s.shouldPause() == playing, "pause only previously playing video");
            s.preview(40);
            UpgridScrubSession.Release r = s.release(true);
            check(r.seek && r.position == 40, "seek to the preview once released");
            check(s.takeResume(r) == playing, "preserve initial playback state");
            check(!s.takeResume(r), "never issue a second automatic Play");
        }
    }

    private static void newerDragOwnsResume() {
        UpgridScrubSession s = new UpgridScrubSession();
        s.begin(true, 12, 100);
        UpgridScrubSession.Release old = s.release(true);
        // Renderer still reports our temporary pause while the old seek is pending.
        s.begin(false, 13, 100);
        check(s.shouldPause(), "new drag inherits the original playing intent");
        check(!s.isCurrent(old) && !s.takeResume(old), "late old reply cannot resume during drag");
        UpgridScrubSession.Release current = s.release(true);
        check(!s.takeResume(old), "old reply also cannot resume after new drag ends");
        check(s.takeResume(current), "only newest drag restores Play");
    }

    private static void explicitPauseWins() {
        for (boolean beforeRelease : new boolean[] {false, true}) {
            UpgridScrubSession s = new UpgridScrubSession();
            s.begin(true, 12, 100);
            if (beforeRelease) s.onPlaybackCommand();
            UpgridScrubSession.Release r = s.release(true);
            if (!beforeRelease) s.onPlaybackCommand();
            check(!s.takeResume(r), "manual playback command invalidates automatic resume");
            check(!s.close(false), "Back cannot undo a later manual Pause");
        }
    }

    private static void backgroundWins() {
        UpgridScrubSession active = new UpgridScrubSession();
        active.begin(true, 12, 100);
        check(!active.close(true), "background during drag must stay paused");
        check(active.release(true) == null, "closed drag cannot seek");
        UpgridScrubSession pending = new UpgridScrubSession();
        pending.begin(true, 12, 100);
        UpgridScrubSession.Release r = pending.release(true);
        check(!pending.close(true), "background while waiting must stay paused");
        check(
                !pending.isCurrent(r) && !pending.takeResume(r),
                "late reply after background is stale");
    }

    private static void backRestoresIntent() {
        for (boolean playing : new boolean[] {false, true}) {
            for (boolean pending : new boolean[] {false, true}) {
                UpgridScrubSession s = new UpgridScrubSession();
                s.begin(playing, 12, 100);
                if (pending) s.release(true);
                check(s.close(false) == playing, "Back restores state preceding temporary pause");
                check(!s.close(false), "close is idempotent");
            }
        }
    }

    private static void cancellationDoesNotSeek() {
        UpgridScrubSession s = new UpgridScrubSession();
        s.begin(true, 12, 100);
        s.preview(80);
        UpgridScrubSession.Release r = s.release(false);
        check(!r.seek, "cancel must not commit the preview position");
        check(s.takeResume(r), "cancel restores previous Play");
    }

    private static void previewIsBounded() {
        UpgridScrubSession s = new UpgridScrubSession();
        check(!s.begin(true, 10, Double.POSITIVE_INFINITY), "reject unbounded live timeline");
        check(!s.begin(true, Double.NaN, 100), "reject invalid position");
        check(!s.begin(true, 0, 0), "reject unknown duration");
        s.begin(true, 12, 100);
        s.preview(-20);
        check(s.position() == 0, "clamp start");
        s.preview(200);
        check(s.position() == 100, "clamp end");
        s.preview(Double.NaN);
        check(
                s.position() == 100 && s.start() == 12 && s.duration() == 100,
                "invalid preview does not alter the gesture baseline");
    }

    private static void duplicateReleaseIsIgnored() {
        UpgridScrubSession s = new UpgridScrubSession();
        s.begin(false, 12, 100);
        check(!s.begin(true, 30, 500), "duplicate begin cannot replace intent");
        UpgridScrubSession.Release r = s.release(true);
        check(r.position == 12 && !s.takeResume(r), "original paused gesture retained");
        check(s.release(true) == null, "one gesture has only one seek release");
    }
}
