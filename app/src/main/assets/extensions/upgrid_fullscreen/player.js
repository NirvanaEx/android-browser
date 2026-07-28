// player.js — page-side half of the built-in video player.
//
// Injected on demand by background.js when the user taps the topbar player
// button. browserAction.onClicked → tabs.executeScript preserves the user
// gesture token, which is what lets us call play() on a <video> the user never
// touched directly.
//
// The file is idempotent: the big guard below defines everything once per
// frame; every subsequent injection only re-runs the takeover entry point at
// the bottom (each tap = a fresh gesture = a fresh takeover).
//
// Protocol (relayed through background.js over the native port):
//   us → native:  {t:"takeover", ok, fs, …state}   takeover result
//                 {t:"state", pos, dur, paused, ended, loop, muted, vw, vh}
//                 {t:"released"}                    we gave the video back
//   native → us:  {cmd:"toggle"}                    play/pause
//                 {cmd:"seekBy", delta}             relative seek, seconds
//                 {cmd:"seekTo", frac}              absolute seek, 0..1
//                 {cmd:"loop"}                      toggle looping
//                 {cmd:"pip", on}                   entering/leaving Android PiP
//                 {cmd:"release"[, silent]}         restore page control
//
// ---------------------------------------------------------------------------
// HOW THE TAKEOVER WORKS, AND WHY IT LOOKS LIKE THIS
//
// Goal: while the player is up, the user must see the video and NOTHING else —
// no site skin, no DOM subtitles, no ambient-mode glow, no playlist panel.
//
// The obvious approach — set `video.style` to fill the viewport — does not
// survive contact with real players. YouTube rewrites the video element's
// inline style on every relayout, so a one-shot assignment is wiped within
// milliseconds and the page shows through. That was the original bug.
//
// So instead:
//
//  1. We build our own STAGE: an opaque, viewport-filling <div> appended to
//     documentElement. Appending to <html> rather than <body> matters — a
//     `transform`/`filter` on an ancestor would make our `position: fixed`
//     resolve against that ancestor instead of the viewport, and pages do
//     transform <body>. Nothing ever transforms <html>.
//
//  2. We MOVE the <video> into the stage. appendChild on an element already in
//     the tree relocates it atomically, so the media element is still in a
//     document when the spec's "await a stable state" check runs and playback
//     is not paused. MSE/blob sources survive this untouched, which is what
//     makes it work on YouTube where the stream can't be extracted at all.
//
//  3. Sizing comes from a <style> rule marked !important, NOT from inline
//     styles. An author rule with !important outranks the page's inline
//     declarations, so the site's player can keep rewriting video.style all it
//     likes and lose.
//
//  4. We re-assert placement on every state tick and via a MutationObserver,
//     because scripted players also re-parent their video back into their own
//     container when they relayout.
//
// There is deliberately NO DOM fullscreen here. The stage already covers the
// content area and the native side hides the browser chrome and system bars,
// so requestFullscreen() bought nothing but a long tail of races: entering it
// fought PiP, exiting it had to be told apart from the user leaving, and
// YouTube would re-grab fullscreen onto its own container anyway. The one
// remnant is that we kick any OTHER element out of fullscreen, since the top
// layer paints above every z-index including ours.
//
// Videos inside iframes are handled by promoting the frame chain: a frame that
// takes over asks its parent (postMessage, which works cross-origin) to give
// the containing <iframe> the same stage treatment, and the parent asks its
// own parent, up to the top document.

(function () {
    "use strict";

    if (!window.__upgridPlayerInit) {
        window.__upgridPlayerInit = true;

        var STAGE_CLASS = "__upgrid-stage";
        var VIDEO_CLASS = "__upgrid-stage-video";
        var STYLE_ID = "__upgrid-stage-style";
        var MSG = "__upgridFrame";

        var active = null;       // the <video> we currently control
        var stage = null;        // our viewport-filling container
        var origin = null;       // where the video came from, for restore
        var observer = null;
        var stateTimer = null;
        var pipMode = false;

        // Promotion state for the iframe path: the frame element we lifted on
        // behalf of a child frame, and where it came from.
        var promoted = null;

        var STATE_EVENTS = ["play", "pause", "seeked", "ended",
                            "durationchange", "volumechange", "loadedmetadata"];

        // Every declaration is !important: these rules have to beat whatever
        // the site's player writes into the element's inline style attribute.
        var CSS = "." + STAGE_CLASS + "{" +
            "position:fixed!important;top:0!important;left:0!important;" +
            "right:0!important;bottom:0!important;width:100%!important;" +
            "height:100%!important;margin:0!important;padding:0!important;" +
            "border:0!important;background:#000!important;" +
            "z-index:2147483647!important;display:flex!important;" +
            "align-items:center!important;justify-content:center!important;" +
            "transform:none!important;filter:none!important;opacity:1!important;" +
            "visibility:visible!important;overflow:hidden!important;" +
            "overscroll-behavior:contain!important;}" +
            "." + VIDEO_CLASS + "{" +
            "position:static!important;width:100%!important;height:100%!important;" +
            "max-width:none!important;max-height:none!important;" +
            "min-width:0!important;min-height:0!important;" +
            "margin:0!important;padding:0!important;border:0!important;" +
            "transform:none!important;filter:none!important;" +
            "object-fit:contain!important;background:#000!important;" +
            "display:block!important;visibility:visible!important;" +
            "opacity:1!important;float:none!important;inset:auto!important;}";

        function send(msg) {
            try { browser.runtime.sendMessage(msg); } catch (e) {}
        }

        function ensureStyle(doc) {
            if (doc.getElementById(STYLE_ID)) return;
            var el = doc.createElement("style");
            el.id = STYLE_ID;
            el.textContent = CSS;
            (doc.head || doc.documentElement).appendChild(el);
        }

        function buildStage(doc) {
            ensureStyle(doc);
            var el = doc.createElement("div");
            el.className = STAGE_CLASS;
            doc.documentElement.appendChild(el);
            return el;
        }

        // --- State ----------------------------------------------------------

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
                // Intrinsic pixel size — the native side turns this into the
                // PiP window's aspect ratio. 0 until metadata lands.
                vw: v ? (v.videoWidth || 0) : 0,
                vh: v ? (v.videoHeight || 0) : 0,
            };
            if (extra) for (var k in extra) s[k] = extra[k];
            return s;
        }

        function pushState() {
            if (!active) return;
            reassert();
            send(snapshot());
        }

        /**
         * Put everything back where the takeover expects it. Scripted players
         * relayout constantly and will re-parent their video or drop our class
         * on the way; this runs on every state tick (500 ms) and from the
         * MutationObserver, so the page can never win for more than a frame.
         */
        function reassert() {
            if (!active || !stage) return;
            var doc = document;
            if (stage.parentNode !== doc.documentElement) {
                doc.documentElement.appendChild(stage);
            }
            if (stage.className.indexOf(STAGE_CLASS) === -1) {
                stage.className = STAGE_CLASS;
            }
            if (active.parentNode !== stage) stage.appendChild(active);
            if (active.className.indexOf(VIDEO_CLASS) === -1) {
                active.classList.add(VIDEO_CLASS);
            }
            if (active.controls) active.controls = false;
            ensureStyle(doc);
            // The top layer paints above every z-index, so a page that grabs
            // fullscreen for its own container would cover the stage outright.
            // exitFullscreen needs no gesture; re-requesting one does, so this
            // cannot ping-pong.
            if (doc.fullscreenElement && doc.fullscreenElement !== stage) {
                try { doc.exitFullscreen(); } catch (e) {}
            }
        }

        function startMonitor(v) {
            stopMonitor();
            active = v;
            STATE_EVENTS.forEach(function (ev) { v.addEventListener(ev, pushState); });
            stateTimer = setInterval(pushState, 500);

            observer = new MutationObserver(function () { reassert(); });
            // Narrow targets on purpose: a subtree observer on documentElement
            // fires thousands of times a second on a page like YouTube.
            observer.observe(stage, { childList: true });
            observer.observe(document.documentElement, { childList: true });
            observer.observe(v, { attributes: true, attributeFilter: ["class", "controls"] });
        }

        function stopMonitor() {
            if (stateTimer) { clearInterval(stateTimer); stateTimer = null; }
            if (observer) { observer.disconnect(); observer = null; }
            if (active) {
                var v = active;
                STATE_EVENTS.forEach(function (ev) { v.removeEventListener(ev, pushState); });
            }
        }

        // --- Take / release --------------------------------------------------

        function takeStage(v) {
            stage = buildStage(document);
            origin = {
                parent: v.parentNode,
                next: v.nextSibling,
                style: v.getAttribute("style"),
                controls: v.controls,
            };
            v.controls = false;
            v.classList.add(VIDEO_CLASS);
            // Atomic move — see the header note on why this doesn't pause.
            stage.appendChild(v);
            askParent("promote");
        }

        function release(opts) {
            if (!active) return;
            var o = opts || {};
            var v = active;
            stopMonitor();
            active = null;
            pipMode = false;

            v.classList.remove(VIDEO_CLASS);
            try {
                if (origin && origin.style !== null) v.setAttribute("style", origin.style);
                else v.removeAttribute("style");
            } catch (e) {}
            try { if (origin) v.controls = origin.controls; } catch (e) {}
            // Back to exactly where it sat in the page, so the site's own
            // player picks up without a reload.
            try {
                if (origin && origin.parent && origin.parent.isConnected !== false) {
                    origin.parent.insertBefore(v, origin.next);
                }
            } catch (e) {}
            origin = null;

            if (stage && stage.parentNode) stage.parentNode.removeChild(stage);
            stage = null;

            askParent("demote");
            if (o.report) send({ t: "released" });
        }

        // --- Iframe chain ----------------------------------------------------

        function askParent(what) {
            if (window === window.top) return;
            try { window.parent.postMessage({ tag: MSG, what: what }, "*"); } catch (e) {}
        }

        /**
         * A child frame took over a video. Lift the <iframe> holding it onto
         * our own stage so the parent document's chrome stops showing around
         * it, then ask our parent to do the same for us.
         */
        function promoteFrame(frameEl) {
            if (promoted) return;
            stage = buildStage(document);
            promoted = {
                el: frameEl,
                parent: frameEl.parentNode,
                next: frameEl.nextSibling,
                style: frameEl.getAttribute("style"),
            };
            frameEl.classList.add(VIDEO_CLASS);
            stage.appendChild(frameEl);
            askParent("promote");
        }

        function demoteFrame() {
            if (!promoted) return;
            var f = promoted.el;
            f.classList.remove(VIDEO_CLASS);
            try {
                if (promoted.style !== null) f.setAttribute("style", promoted.style);
                else f.removeAttribute("style");
            } catch (e) {}
            try { promoted.parent.insertBefore(f, promoted.next); } catch (e) {}
            promoted = null;
            if (stage && stage.parentNode) stage.parentNode.removeChild(stage);
            stage = null;
            askParent("demote");
        }

        window.addEventListener("message", function (e) {
            var d = e.data;
            if (!d || d.tag !== MSG) return;
            // event.source identifies the child window even cross-origin,
            // which is the only reliable way to find which iframe spoke.
            var frames = document.querySelectorAll("iframe, frame");
            for (var i = 0; i < frames.length; i++) {
                if (frames[i].contentWindow === e.source) {
                    if (d.what === "promote") promoteFrame(frames[i]);
                    else demoteFrame();
                    return;
                }
            }
        });

        // --- Video selection -------------------------------------------------

        function pickVideo() {
            var vids = Array.prototype.slice.call(document.querySelectorAll("video"));
            if (!vids.length) return null;
            // Ad slots and lazy players leave 0×0 / display:none <video> tags
            // lying around; taking one over yields a black screen with a
            // working seek bar. Ignore anything with no layout box.
            var onScreen = vids.filter(function (v) {
                var r = v.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
            });
            if (onScreen.length) vids = onScreen;
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

            release({ report: false });
            takeStage(v);
            if (v.paused) { try { v.play().catch(function () {}); } catch (e) {} }
            startMonitor(v);
            reassert();
            // fs stays true: the stage IS our fullscreen presentation, and the
            // native side keys its chrome-hiding off this flag.
            send(snapshot({ t: "takeover", ok: true, fs: true }));
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
                case "pip":
                    // Nothing to negotiate any more: the stage is plain layout,
                    // not DOM fullscreen, so an activity resize can't knock it
                    // over. Kept so the native side has one flag to reason with.
                    pipMode = !!msg.on;
                    reassert();
                    pushState();
                    break;
                case "release":
                    release({ report: !msg.silent });
                    break;
            }
        });
    }

    // Every injection re-enters here carrying the tap's gesture token.
    // The completion value ("ok"/"none") surfaces per-frame in background.js's
    // executeScript results so it can detect the "no video anywhere" case.
    return window.__upgridTakeover();
})();
