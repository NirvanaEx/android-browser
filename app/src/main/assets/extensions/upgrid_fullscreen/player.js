// player.js — page-side half of the built-in video player.
//
// Injected on demand by background.js when the user taps the topbar player
// button. browserAction.onClicked → tabs.executeScript preserves the user
// gesture token, which is what lets us call requestFullscreen() + play()
// without involving the page's own controls.
//
// The file is idempotent: the big guard below defines everything once per
// frame; every subsequent injection only re-runs the takeover entry point at
// the bottom (each tap = a fresh gesture = a fresh takeover).
//
// Protocol (relayed through background.js over the native port):
//   us → native:  {t:"takeover", ok, fs, …state}   takeover result
//                 {t:"state", pos, dur, paused, ended, loop, muted}
//                 {t:"released"}                    we gave the video back
//   native → us:  {cmd:"toggle"}                    play/pause
//                 {cmd:"seekBy", delta}             relative seek, seconds
//                 {cmd:"seekTo", frac}              absolute seek, 0..1
//                 {cmd:"loop"}                      toggle looping
//                 {cmd:"release"[, silent]}         restore page control

(function () {
    "use strict";

    if (!window.__upgridPlayerInit) {
        window.__upgridPlayerInit = true;

        var active = null;       // the <video> we currently control
        var hadControls = false; // page's video.controls value before takeover
        var hadStyle = "";       // page's video inline style before takeover
        var stateTimer = null;

        // While we're in charge the video is force-promoted to a fixed,
        // viewport-filling, black-backed top layer. This is what guarantees
        // the Banana-style "only the video + our overlay" look in EVERY case:
        //  - element fullscreen: harmless (top-layer UA styles win anyway);
        //  - the site re-grabs fullscreen onto its own container: the video
        //    still covers the container, burying the site's skin;
        //  - fullscreen rejected entirely: the video covers the viewport and
        //    the native side hides the browser chrome — a perfect fake
        //    fullscreen.
        var NUKE_STYLE = ";position:fixed!important;top:0!important;left:0!important" +
            ";right:0!important;bottom:0!important;width:100vw!important" +
            ";height:100vh!important;max-width:none!important;max-height:none!important" +
            ";margin:0!important;padding:0!important;transform:none!important" +
            ";z-index:2147483646!important;background:#000!important" +
            ";object-fit:contain!important;";

        var STATE_EVENTS = ["play", "pause", "seeked", "ended",
                            "durationchange", "volumechange"];

        function send(msg) {
            try { browser.runtime.sendMessage(msg); } catch (e) {}
        }

        function snapshot(extra) {
            var v = active;
            var s = {
                t: "state",
                pos: v ? (v.currentTime || 0) : 0,
                dur: (v && isFinite(v.duration)) ? v.duration : 0,
                paused: v ? v.paused : true,
                ended: v ? v.ended : false,
                loop: v ? v.loop : false,
                muted: v ? v.muted : false,
            };
            if (extra) for (var k in extra) s[k] = extra[k];
            return s;
        }

        function pushState() { if (active) send(snapshot()); }

        function startMonitor(v) {
            stopMonitor();
            active = v;
            STATE_EVENTS.forEach(function (ev) { v.addEventListener(ev, pushState); });
            stateTimer = setInterval(pushState, 500);
        }

        function stopMonitor() {
            if (stateTimer) { clearInterval(stateTimer); stateTimer = null; }
            if (active) {
                var v = active;
                STATE_EVENTS.forEach(function (ev) { v.removeEventListener(ev, pushState); });
            }
        }

        function release(report) {
            if (!active) return;
            var v = active;
            stopMonitor();
            active = null;
            try { v.controls = hadControls; } catch (e) {}
            try { v.style.cssText = hadStyle; } catch (e) {}
            if (document.fullscreenElement) {
                try { document.exitFullscreen(); } catch (e) {}
            }
            if (report) send({ t: "released" });
        }

        // Fullscreen collapsed through any path we didn't initiate (system
        // back, page script, tab switch) → give the video back to the page.
        document.addEventListener("fullscreenchange", function () {
            if (!document.fullscreenElement && active) release(true);
        });

        function pickVideo() {
            var vids = Array.prototype.slice.call(document.querySelectorAll("video"));
            if (!vids.length) return null;
            // Prefer whatever is actually playing; fall back to anything with
            // metadata; last resort, any <video>. Within the pool, biggest on
            // screen wins — that's "the" player on multi-video pages.
            var playing = vids.filter(function (v) {
                return !v.paused && !v.ended && v.readyState >= 2;
            });
            var pool = playing.length ? playing
                     : vids.filter(function (v) { return v.readyState >= 1; });
            if (!pool.length) pool = vids;
            pool.sort(function (a, b) {
                var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
                return rb.width * rb.height - ra.width * ra.height;
            });
            return pool[0];
        }

        window.__upgridTakeover = function () {
            var v = pickVideo();
            if (!v) return "none";

            release(false);
            hadControls = v.controls;
            hadStyle = v.style.cssText;
            // Strip the page's UI: native controls off, then promote the
            // video to a viewport-filling top layer so the page's custom
            // control DOM (site skins, scrubbers, watermark bars) is buried
            // beneath it — our native overlay is the only chrome visible.
            v.controls = false;
            try { v.style.cssText = hadStyle + NUKE_STYLE; } catch (e) {}
            if (v.paused) { try { v.play().catch(function () {}); } catch (e) {} }

            var fsOk = false;
            if (document.fullscreenElement === v) {
                fsOk = true;
            } else {
                var req = v.requestFullscreen || v.webkitRequestFullscreen || v.mozRequestFullScreen;
                if (req) {
                    try {
                        var p = req.call(v);
                        if (p && p.catch) p.catch(function () {});
                        fsOk = true;
                    } catch (e) {}
                }
            }

            startMonitor(v);
            send(snapshot({ t: "takeover", ok: true, fs: fsOk }));
            return "ok";
        };

        browser.runtime.onMessage.addListener(function (msg) {
            if (!msg || !msg.cmd || !active) return;
            var v = active;
            switch (msg.cmd) {
                case "toggle":
                    if (v.paused || v.ended) {
                        try { v.play().catch(function () {}); } catch (e) {}
                    } else {
                        v.pause();
                    }
                    break;
                case "seekBy": {
                    var target = (v.currentTime || 0) + (msg.delta || 0);
                    if (isFinite(v.duration)) target = Math.min(v.duration, target);
                    v.currentTime = Math.max(0, target);
                    pushState();
                    break;
                }
                case "seekTo":
                    if (isFinite(v.duration) && v.duration > 0) {
                        v.currentTime = v.duration * Math.max(0, Math.min(1, msg.frac || 0));
                    }
                    pushState();
                    break;
                case "loop":
                    v.loop = !v.loop;
                    pushState();
                    break;
                case "release":
                    release(!msg.silent);
                    break;
            }
        });
    }

    // Every injection re-enters here carrying the tap's gesture token.
    // The completion value ("ok"/"none") surfaces per-frame in background.js's
    // executeScript results so it can detect the "no video anywhere" case.
    return window.__upgridTakeover();
})();
