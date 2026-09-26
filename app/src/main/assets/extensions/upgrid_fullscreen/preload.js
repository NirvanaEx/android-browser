// Learn about videos from media/user events, without scanning the DOM on page load.
// Only opaque identities and geometry leave this frame; URLs remain in its closure.
(function () {
    "use strict";
    if (window.__upgridPlayerPreloaded) return;
    window.__upgridPlayerPreloaded = true;
    var known = [], selected = null, serial = 0;
    var instance = Math.random().toString(36).slice(2) + Date.now().toString(36);
    function visible(video) {
        if (!video || !video.isConnected || video.error) return false;
        var rect = video.getBoundingClientRect(), css = getComputedStyle(video);
        return rect.width > 0 && rect.height > 0 && css.display !== "none" && css.visibility !== "hidden";
    }
    function best() {
        known = known.filter(video => video.isConnected);
        return known.filter(visible).sort(function (a, b) {
            var playingA = !a.paused && !a.ended && a.readyState >= 2;
            var playingB = !b.paused && !b.ended && b.readyState >= 2;
            if (playingA !== playingB) return playingB ? 1 : -1;
            var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
            return rb.width * rb.height - ra.width * ra.height;
        })[0];
    }
    function report() {
        var video = best();
        if (video) {
            var source = video.currentSrc || video.src;
            if (!selected || selected.video !== video || selected.source !== source) {
                selected = {video:video, source:source, key:instance + "-" + (++serial)};
            }
            var rect = video.getBoundingClientRect();
            browser.runtime.sendMessage({t:"video_hint", key:selected.key,
                playing:!video.paused && !video.ended && video.readyState >= 2,
                area:rect.width * rect.height}).catch(function () {});
        } else {
            selected = null;
            browser.runtime.sendMessage({t:"video_hint", key:null}).catch(function () {});
        }
    }
    function learn(event) {
        if (event.type === "pointerdown" && !event.isTrusted) return;
        var video = event.composedPath()[0];
        if (!video || video.tagName !== "VIDEO") return;
        if (!known.includes(video)) {
            // A bounded cache, no per-video listeners or observers to retain old posts.
            known.push(video);
            if (known.length > 16) known.shift();
        }
        report();
    }
    for (var type of ["playing", "pause", "ended", "loadedmetadata", "emptied", "pointerdown"])
        document.addEventListener(type, learn, true);
    window.addEventListener("pagehide", function () { known = []; selected = null; report(); });
    browser.runtime.onMessage.addListener(function (message) {
        if (!message || message.cmd !== "fast_takeover") return;
        // A navigation, recycled element, hidden video or a changed winner must fall
        // back to discovery. Never apply an old hint to a neighbouring video.
        if (document.hidden || !selected || message.key !== selected.key ||
                best() !== selected.video || (selected.video.currentSrc || selected.video.src) !== selected.source) {
            return Promise.resolve("miss");
        }
        return upgridPlayerMain(message.requestId, message.token, selected.video, true);
    });
})();
