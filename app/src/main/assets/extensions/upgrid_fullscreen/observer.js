// observer.js — always-on media watcher, one instance per frame.
//
// Separate from player.js on purpose. player.js is the *takeover*: injected by
// a browser-action click, it strips the page player and hands the video to our
// overlay. This script only watches, and it runs everywhere, all the time,
// because three separate features need to know whether a video is playing
// before the user has touched anything:
//
//   - the topbar ▶ button, which should exist only when there's something to
//     take over;
//   - pausing playback when the user leaves the tab;
//   - and the honest answer to "is the player ready", instead of a toast that
//     fires while a video is visibly running.
//
// It is deliberately quiet: report only on change, no polling until something
// has actually appeared, and a debounce on the DOM observer because a video
// page mutates constantly.

(function () {
    if (window.__upgridMediaObserver) return;
    window.__upgridMediaObserver = true;

    var lastKey = null;
    var pollTimer = null;
    var debounce = null;

    function survey() {
        var nodes = document.querySelectorAll("video, audio");
        var has = false;
        var playing = false;
        for (var i = 0; i < nodes.length; i++) {
            var m = nodes[i];
            // Ad frames leave empty <video> shells lying around. One with no
            // source and nothing buffered is not something to offer the user.
            if (!m.currentSrc && !m.src && m.readyState === 0) continue;
            has = true;
            if (!m.paused && !m.ended && m.readyState >= 2) playing = true;
        }
        return { has: has, playing: playing };
    }

    function report(force) {
        var state = survey();
        var key = (state.has ? "1" : "0") + (state.playing ? "1" : "0");
        if (!force && key === lastKey) return;
        lastKey = key;

        // Poll only while media exists. A timer on every frame of every page
        // is a battery bug waiting to happen; some players swap sources
        // without firing an event we can hear, so once there IS a video a slow
        // tick is worth it.
        if (state.has && !pollTimer) {
            pollTimer = setInterval(function () { report(false); }, 2000);
        } else if (!state.has && pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }

        try {
            browser.runtime.sendMessage({ t: "media", has: state.has, playing: state.playing });
        } catch (e) {}
    }

    // Capture phase: media events don't bubble, so a listener on the document
    // only sees them on the way down.
    ["play", "playing", "pause", "ended", "emptied", "loadeddata", "abort"]
        .forEach(function (type) {
            document.addEventListener(type, function () { report(false); }, true);
        });

    // Debounced: on a video site this fires hundreds of times a second, and
    // the answer can't change faster than the user can perceive.
    new MutationObserver(function () {
        if (debounce) return;
        debounce = setTimeout(function () { debounce = null; report(false); }, 400);
    }).observe(document.documentElement, { childList: true, subtree: true });

    browser.runtime.onMessage.addListener(function (msg) {
        // Shares the message channel with player.js, which has its own command
        // vocabulary — anything not ours is somebody else's.
        if (!msg || msg.cmd !== "pauseAll") return;
        var nodes = document.querySelectorAll("video, audio");
        for (var i = 0; i < nodes.length; i++) {
            try { nodes[i].pause(); } catch (e) {}
        }
        report(true);
    });

    report(true);
})();
