// taps.js — tell the app when a link was actually followed.
//
// One job, and it exists because the app can't answer this by itself. Native
// code sees a touch on the engine view; it has no idea whether the finger
// landed on a link, on a paragraph, or on the background. The page knows, and
// the answer is one `closest("a[href]")`.
//
// The app uses it for a single short haptic tick — the confirmation that a tap
// registered. A browser is the one app where you routinely can't tell: the
// page doesn't move until the network answers, and on a slow site that pause
// is long enough to tap again somewhere you didn't mean to.
//
// Deliberately passive:
//
//   - the listener never calls preventDefault and never inspects the href, so
//     it cannot change what a link does;
//   - it reports the *event*, not the URL — nothing about where the user is
//     going leaves the page;
//   - capture phase, so a page that stops the event from bubbling (most
//     single-page apps) is still felt;
//   - `passive: true`, so it can never delay a scroll.
//
// Runs in every frame, which is where the links are: on an aggregator or a
// mail client the whole reading pane is an iframe.

(function () {
    if (window.__upgridTaps) return;
    window.__upgridTaps = true;

    // A tap on a link is a click on the anchor or on anything inside it — the
    // target is usually a <span> or an <img>, never the <a> itself.
    function isFollowable(target) {
        if (!target || !target.closest) return false;
        return target.closest('a[href], area[href], [role="link"]') !== null;
    }

    document.addEventListener(
        "click",
        function (event) {
            // Synthetic clicks are how carousels, menus and analytics move
            // themselves along; buzzing for those would make the phone twitch
            // at things the user didn't touch.
            if (!event.isTrusted) return;
            if (!isFollowable(event.target)) return;
            try {
                browser.runtime.sendMessage({ t: "tap" });
            } catch (e) {}
        },
        { capture: true, passive: true },
    );
})();
