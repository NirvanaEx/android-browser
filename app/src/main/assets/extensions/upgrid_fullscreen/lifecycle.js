// Pause on leaving the app/tab. Site autoplay cannot resume it on return;
// a real user input or the native Play action is required.
(function () {
    "use strict";
    if (window.__upgridMediaLifecycle) return;
    var held = new Set();
    function allow(video) { held.delete(video); video.removeEventListener("play", blockHeld); }
    function blockHeld(event) {
        if (held.has(event.currentTarget) || document.hidden) event.currentTarget.pause();
    }
    function suspend(video) {
        if (!video || video.tagName !== "VIDEO") return;
        held.add(video);
        video.addEventListener("play", blockHeld);
        video.pause();
    }
    function videos(root) {
        var result = Array.from(root.querySelectorAll("video"));
        for (var element of root.querySelectorAll("*")) {
            if (element.shadowRoot) result = result.concat(videos(element.shadowRoot));
        }
        return result;
    }
    function suspendPage() {
        for (var old of held) if (!old.isConnected) allow(old);
        for (var video of videos(document)) if (!video.paused || held.has(video)) suspend(video);
    }
    function blockAutoplay(event) {
        var video = event.composedPath()[0];
        if (video?.tagName === "VIDEO" && (document.hidden || held.has(video))) suspend(video);
    }
    document.addEventListener("play", blockAutoplay, true);
    document.addEventListener("visibilitychange", function () { if (document.hidden) suspendPage(); });
    window.addEventListener("pagehide", suspendPage);
    // Browser-delivered input only. A synthetic click from a site is not consent.
    for (var type of ["pointerdown", "keydown"]) document.addEventListener(type, function (event) {
        if (event.isTrusted && !document.hidden) for (var video of held) allow(video);
    }, true);
    browser.runtime.onMessage.addListener(function (message) {
        if (message?.cmd === "suspend_page") suspendPage();
    });
    window.__upgridMediaLifecycle = { suspend: suspend, allow: allow };
})();
