// Learn about videos from media/user events, without scanning the DOM on page load.
// Only opaque identities and geometry leave this frame; URLs remain in its closure.
(function () {
    "use strict";
    if (window.__upgridPlayerPreloaded) return;
    window.__upgridPlayerPreloaded = true;
    var known = [], selected = null, serial = 0;
    var instance = Math.random().toString(36).slice(2) + Date.now().toString(36);
    var automaticFullscreen = false; // Enabled by the Fenix overlay.
    function fullscreenVideo() {
        var element = document.fullscreenElement;
        while (element && element.shadowRoot && element.shadowRoot.fullscreenElement)
            element = element.shadowRoot.fullscreenElement;
        return element && element.tagName === "VIDEO" ? element : null;
    }
    function visible(video) {
        if (!video || !video.isConnected) return false;
        if (video.error && !(automaticFullscreen && !video.mediaKeys && !video.srcObject &&
                /^https?:\/\//i.test(video.currentSrc || video.src || ""))) return false;
        var rect = video.getBoundingClientRect(), css = getComputedStyle(video);
        return rect.width > 0 && rect.height > 0 && css.display !== "none" && css.visibility !== "hidden";
    }
    function best() {
        known = known.filter(video => video.isConnected);
        return known.filter(visible).sort(function (a, b) {
            var playingA = !a.error && !a.paused && !a.ended && a.readyState >= 2;
            var playingB = !b.error && !b.paused && !b.ended && b.readyState >= 2;
            if (playingA !== playingB) return playingB ? 1 : -1;
            if (!!a.error !== !!b.error) return a.error ? 1 : -1;
            var audibleA = playingA && !a.muted && a.volume !== 0;
            var audibleB = playingB && !b.muted && b.volume !== 0;
            if (audibleA !== audibleB) return audibleB ? 1 : -1;
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
                playing:!video.error && !video.paused && !video.ended && video.readyState >= 2,
                failed:!!video.error,
                audible:!video.error && !video.paused && !video.muted && video.volume !== 0,
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
    for (var type of ["play", "playing", "pause", "ended", "loadedmetadata", "emptied", "error", "volumechange", "pointerdown"])
        document.addEventListener(type, learn, true);
    // Fullscreen granted by Gecko is the authority. Do not infer it from a site's
    // CSS, synthetic clicks or messages, and never change activation preferences.
    document.addEventListener("fullscreenchange", function () {
        // Let the previous video's fullscreen-exit listener release its capture
        // before claiming a newly fullscreened video in the same document.
        Promise.resolve().then(function () {
            var video = fullscreenVideo();
            if (!automaticFullscreen || !video || document.hidden || window.__upgridOwnsFullscreen?.()) return;
            if (!known.includes(video)) { known.push(video); if (known.length > 16) known.shift(); }
            selected = {video:video, source:video.currentSrc || video.src, key:instance + "-" + (++serial)};
            browser.runtime.sendMessage({t:"site_fullscreen", key:selected.key}).catch(function () {});
        });
    }, true);
    // Redirect a website's video-container request to that same video while its
    // original user activation is still on the stack. Non-video fullscreen stays
    // with the site. The native API still enforces iframe policy and permissions.
    if (automaticFullscreen && typeof exportFunction === "function" && window.wrappedJSObject) {
        try {
            var page = window.wrappedJSObject;
            var prototype = page.Element.prototype;
            var original = Element.prototype.requestFullscreen;
            var queryVideos = Element.prototype.querySelectorAll;
            var contains = Element.prototype.contains;
            var redirect = exportFunction(function (options) {
                var target = this, videos = [];
                try {
                    if (target === page.document.documentElement || target === page.document.body)
                        return original.call(target, options);
                    videos = Array.from(queryVideos.call(target, "video")).filter(visible);
                    if (videos.length) {
                        var current = best();
                        target = current && contains.call(this, current) ? current :
                            videos.sort((a, b) => Number(!b.paused && !b.ended) - Number(!a.paused && !a.ended) ||
                                b.getBoundingClientRect().width * b.getBoundingClientRect().height -
                                a.getBoundingClientRect().width * a.getBoundingClientRect().height)[0];
                    }
                } catch (_) { target = this; }
                return original.call(target, options);
            }, page);
            Object.defineProperty(prototype, "requestFullscreen", {value:redirect, writable:true, configurable:true});
        } catch (_) { /* Native video controls and standard fullscreen events still work. */ }
    }
    window.addEventListener("pagehide", function () { known = []; selected = null; report(); });
    browser.runtime.onMessage.addListener(function (message) {
        if (message && message.cmd === "adopt_fullscreen") {
            if (document.hidden || !selected || selected.key !== message.key || fullscreenVideo() !== selected.video ||
                (selected.video.currentSrc || selected.video.src) !== selected.source) return Promise.resolve("stale");
            return upgridPlayerMain(message.requestId, message.key, selected.video, true);
        }
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
