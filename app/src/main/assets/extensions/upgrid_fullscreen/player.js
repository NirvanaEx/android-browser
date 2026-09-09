// Keep the original media session alive while presenting a player page.
function upgridPlayerMain(requestId, token) {
    "use strict";
    if (!window.__upgridPagePlayer) {
        var currentId = null, currentToken = null, candidate = null, active = null;
        var nativeSession = null, engineMode = false, engineSource = null, engineFacebookId = null, engineTrigger = null;
        var hadControls = false, ready = false, timer = null, pending = null, parentReply = null;
        var savedStyles = new Map();
        var engineFit = null;
        var styleRules = new Map();
        var isolated = null, isolationObserver = null;
        var events = ["play", "pause", "seeked", "ended", "durationchange", "volumechange"];
        function send(message) {
            return browser.runtime.sendMessage(Object.assign({ requestId: currentId }, message)).catch(function () {});
        }
        function snapshot(extra) {
            return Object.assign({
                t: "state", pos: active ? active.currentTime || 0 : 0,
                dur: active && isFinite(active.duration) ? active.duration : 0,
                paused: !active || active.paused, ended: !!(active && active.ended),
                loop: !!(active && active.loop), muted: !!(active && active.muted),
            }, extra || {});
        }
        function pushState() {
            if (active && (!active.isConnected || active.error)) { release(true); return; }
            if (engineMode && active && !isVideoFullscreen(active)) { release(true); return; }
            if (engineMode && active && ((active.currentSrc || active.src) !== engineSource ||
                (engineFacebookId && facebookIdentity(active)?.id !== engineFacebookId))) { release(true); return; }
            if (active && ready) send(snapshot());
        }
        function style(element, value) {
            if (!savedStyles.has(element)) savedStyles.set(element, element.getAttribute("style"));
            if (!styleRules.has(value)) {
                var rule = document.createElement("span").style, declarations = [];
                rule.cssText = value;
                for (var i = 0; i < rule.length; i++) declarations.push([rule.item(i), rule.getPropertyValue(rule.item(i))]);
                styleRules.set(value, declarations);
            }
            styleRules.get(value).forEach(function (declaration) {
                var name = declaration[0], content = declaration[1];
                if (element.style.getPropertyValue(name) !== content || element.style.getPropertyPriority(name) !== "important") {
                    element.style.setProperty(name, content, "important");
                }
            });
        }
        function parentOf(element) {
            return element.parentElement || (element.getRootNode && element.getRootNode().host) || null;
        }
        function isolate(element) {
            // Preserve the DOM: moving a video can reset MediaSource playback.
            isolated = element;
            if (!isolationObserver) isolationObserver = new MutationObserver(function () {
                if (isolated && isolated.isConnected) refreshIsolation();
                else release(true);
            });
            refreshIsolation();
        }
        function refreshIsolation() {
            isolationObserver.disconnect();
            var element = isolated, watched = new Set();
            var child = element, parent = parentOf(child);
            while (parent) {
                var container = child.parentElement || child.getRootNode();
                watched.add(container);
                watched.add(parent);
                Array.prototype.forEach.call(container.children || [], function (sibling) {
                    if (sibling !== child && !sibling.contains(child)) {
                        // Opacity hides the whole subtree even if a child sets visibility:visible.
                        style(sibling, "visibility:hidden!important;opacity:0!important;pointer-events:none!important");
                        isolationObserver.observe(sibling, { attributes: true, attributeFilter: ["style"] });
                    }
                });
                style(parent, "transform:none!important;filter:none!important;perspective:none!important;" +
                    "contain:none!important;content-visibility:visible!important;overflow:visible!important;" +
                    "clip:auto!important;clip-path:none!important;opacity:1!important;visibility:visible!important;" +
                    "min-width:0!important;min-height:0!important;max-width:none!important;max-height:none!important");
                child = parent;
                parent = parentOf(parent);
            }
            style(document.documentElement, "overflow:hidden!important;background:#000!important");
            style(element, "position:fixed!important;inset:0!important;left:0!important;top:0!important;" +
                "width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;" +
                "min-width:0!important;min-height:0!important;margin:0!important;padding:0!important;border:0!important;" +
                "transform:none!important;visibility:visible!important;opacity:1!important;" +
                "z-index:2147483647!important;background:#000!important;object-fit:contain!important");
            if (active && active.controls) active.controls = false;
            watched.add(element);
            watched.forEach(function (node) {
                isolationObserver.observe(node, { childList: true, attributes: true, attributeFilter: ["style", "controls"] });
            });
        }
        function release(report) {
            var oldId = currentId, wasActive = !!active;
            if (engineTrigger) engineTrigger.remove();
            engineTrigger = null;
            returnStream(null, true);
            if (engineMode && active) {
                suspendVideo(active);
                if (isVideoFullscreen(active)) document.exitFullscreen().catch(function () {});
            }
            engineMode = false;
            engineSource = engineFacebookId = null;
            currentId = null;
            currentToken = null;
            ready = false;
            pending = null;
            if (isolationObserver) isolationObserver.disconnect();
            isolationObserver = null;
            isolated = null;
            if (parentReply) { parentReply(false); parentReply = null; }
            clearInterval(timer);
            timer = null;
            if (active) {
                events.forEach(function (event) { active.removeEventListener(event, pushState); });
                active.controls = hadControls;
                if (engineFit) {
                    engineFit.properties.forEach(function (property) {
                        if (active.style.getPropertyValue(property.name) !== property.applied) return;
                        if (property.value) active.style.setProperty(property.name, property.value, property.priority);
                        else active.style.removeProperty(property.name);
                    });
                    if (!engineFit.hadStyle && !active.getAttribute("style")) active.removeAttribute("style");
                }
            }
            engineFit = null;
            active = null;
            savedStyles.forEach(function (value, element) {
                if (value === null) element.removeAttribute("style"); else element.setAttribute("style", value);
            });
            savedStyles.clear();
            if (report && wasActive) browser.runtime.sendMessage({ t: "released", requestId: oldId }).catch(function () {});
        }
        function findElements(root, selector) {
            var found = Array.prototype.slice.call(root.querySelectorAll(selector));
            Array.prototype.forEach.call(root.querySelectorAll("*"), function (element) {
                if (element.shadowRoot) found = found.concat(findElements(element.shadowRoot, selector));
            });
            return found;
        }
        function pickVideo() {
            var videos = findElements(document, "video").filter(function (video) {
                var rect = video.getBoundingClientRect(), css = window.getComputedStyle(video);
                return video.isConnected && !video.error && rect.width > 0 && rect.height > 0 &&
                    css.display !== "none" && css.visibility !== "hidden";
            });
            videos.sort(function (a, b) {
                var playingA = !a.paused && !a.ended && a.readyState >= 2;
                var playingB = !b.paused && !b.ended && b.readyState >= 2;
                if (playingA !== playingB) return playingB ? 1 : -1;
                var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
                return rb.width * rb.height - ra.width * ra.height;
            });
            return videos[0] || null;
        }
        function facebookData(object, key) {
            return object && Object.getOwnPropertyDescriptor(object, key)?.value;
        }
        function suspendVideo(video) {
            video.pause();
            if (window.__upgridMediaLifecycle) window.__upgridMediaLifecycle.suspend(video);
        }
        function resumeByUser(video) {
            if (window.__upgridMediaLifecycle) window.__upgridMediaLifecycle.allow(video);
        }
        function isVideoFullscreen(video) {
            var root = video.getRootNode && video.getRootNode();
            return (root && root.fullscreenElement || document.fullscreenElement) === video;
        }
        // Read public APIs only after verifying that a player owns this exact element.
        // No global network sniffing: unrelated ads/posts must never become candidates.
        function playerSources(video) {
            var result = [], raw = video.wrappedJSObject || video, page = window.wrappedJSObject || window;
            function add(url, type) {
                if (typeof url !== "string" || url.length > 8192 || result.length >= 8) return;
                try {
                    var parsed = new URL(url, document.baseURI);
                    if (!/^https?:$/.test(parsed.protocol) || parsed.username || parsed.password) return;
                    if (!result.some(function (item) { return item.url === parsed.href; }))
                        result.push({ url: parsed.href, mimeType: typeof type === "string" ? type.slice(0, 100) : "" });
                } catch (_) {}
            }
            add(video.currentSrc || video.src, video.getAttribute("type"));
            if (result.length && video.querySelectorAll) {
                Array.from(video.querySelectorAll("source")).slice(0, 8).forEach(function (source) { add(source.src, source.type); });
            }
            try {
                var players = page.videojs?.getPlayers();
                Object.keys(players || {}).slice(0, 32).forEach(function (key) {
                    try {
                        var player = players[key];
                        if (player.tech(true)?.el() !== raw) return;
                        (player.currentSources() || []).slice(0, 8).forEach(function (source) { add(source.src, source.type); });
                    } catch (_) {}
                });
            } catch (_) {}
            try {
                for (var n = 0; n < 8 && typeof page.jwplayer === "function"; n++) {
                    var jw = page.jwplayer(n), container = jw.getContainer();
                    if (!container) break;
                    if (!container.contains(raw) || container.querySelectorAll("video").length !== 1) continue;
                    var item = jw.getPlaylistItem();
                    if (!item || (item.duration && isFinite(video.duration) && Math.abs(item.duration - video.duration) > 2)) continue;
                    (item.sources || [item]).slice(0, 8).forEach(function (source) { if (!source.drm) add(source.file, source.type); });
                }
            } catch (_) {}
            // Hls.js / dash.js integrations commonly retain their instance on the
            // element or window. Accept it only with an exact media-element match.
            for (var holder of [raw, page]) {
                for (var name of ["hls", "dash", "player"]) {
                    try {
                        var instance = facebookData(holder, name);
                        if (instance?.media === raw) add(instance.url, "application/x-mpegURL");
                        if (typeof instance?.getVideoElement === "function" && instance.getVideoElement() === raw)
                            add(instance.getSource(), "application/dash+xml");
                        if (typeof instance?.getMediaElement === "function" && instance.getMediaElement() === raw)
                            add(instance.getAssetUri(), "");
                    } catch (_) {}
                }
            }
            try {
                var ancestor = raw.parentElement;
                for (var depth = 0; ancestor && depth < 6; depth++, ancestor = ancestor.parentElement) {
                    var shaka = ancestor.ui?.getControls()?.getPlayer();
                    if (shaka?.getMediaElement() === raw) add(shaka.getAssetUri(), "");
                }
            } catch (_) {}
            return result;
        }
        function facebookHasDrm(value) {
            if (value == null) return false;
            try {
                if (typeof value === "string" && value.length < 16384) value = JSON.parse(value);
                var licenses = facebookData(value, "video_license_uri_map");
                // Facebook includes a public Widevine certificate on clear videos
                // too. Only a recognized empty license configuration is accepted.
                return !licenses || typeof licenses !== "object" || Object.keys(licenses).length > 0 ||
                    !!facebookData(value, "graph_api_video_license_uri");
            } catch (_) { return true; }
        }
        function facebookIdentity(video) {
            try {
                if (!/(^|\.)facebook\.com$/.test(new URL(document.URL).hostname)) return null;
                // Read only data fields from the selected element's React ancestry.
                // Never call page functions or read access tokens/player credentials.
                var element = video.wrappedJSObject || video;
                var key = Object.keys(element).find(function (name) { return name.indexOf("__reactFiber$") === 0; });
                var fiber = facebookData(element, key);
                for (var depth = 0; fiber && depth < 32; depth++, fiber = facebookData(fiber, "return")) {
                    var meta = facebookData(facebookData(fiber, "memoizedProps"), "coreVideoPlayerMetaData");
                    var id = facebookData(meta, "videoFBID");
                    if (typeof id === "string" && /^\d{5,32}$/.test(id)) {
                        return { id: id, protected: facebookHasDrm(facebookData(meta, "graphQLVideoDRMInfo")),
                            live: !!facebookData(meta, "isLiveStreaming") };
                    }
                }
            } catch (_) {}
            return null;
        }
        async function facebookStream(video) {
            var identity = facebookIdentity(video);
            if (!identity) return null;
            if (identity.protected) return { error: "protected_stream" };
            if (identity.live) return null;
            // Facebook publishes progressive MP4 alternatives alongside its MSE
            // manifest. Match the actual player ID, never a neighbouring post or
            // an arbitrary observed network request. No additional page requests.
            var scripts = document.querySelectorAll('script[type="application/json"][data-sjs]');
            // Cold GeckoView execution can exceed 120 ms even for a valid page.
            // Give the scan time, yielding between short chunks to keep UI live.
            var budget = 4 * 1024 * 1024, nodes = 20000, deadline = Date.now() + 1500;
            var sliceEnd = Date.now() + 12;
            for (var i = 0; i < scripts.length && budget > 0 && Date.now() < deadline; i++) {
                if (Date.now() >= sliceEnd) {
                    await new Promise(function (resolve) { setTimeout(resolve, 0); });
                    sliceEnd = Date.now() + 12;
                    if (Date.now() >= deadline) break;
                }
                var text = scripts[i].textContent || "";
                budget -= text.length;
                if (budget < 0) break;
                if (text.indexOf('"' + identity.id + '"') < 0) continue;
                var stack;
                try { stack = [JSON.parse(text)]; } catch (_) { continue; }
                while (stack.length && --nodes > 0 && Date.now() < deadline) {
                    if (Date.now() >= sliceEnd) {
                        await new Promise(function (resolve) { setTimeout(resolve, 0); });
                        sliceEnd = Date.now() + 12;
                        if (Date.now() >= deadline) break;
                    }
                    var item = stack.pop();
                    if (!item || typeof item !== "object") continue;
                    Object.keys(item).forEach(function (key) {
                        if (item[key] && typeof item[key] === "object") stack.push(item[key]);
                    });
                    if (item.__typename === "Video" && (item.id || item.videoId) === identity.id) {
                        if (facebookHasDrm(item.drm_info)) return { error: "protected_stream" };
                        var duration = Number(item.playable_duration_in_ms);
                        if (item.is_live_streaming || !isFinite(video.duration) || !duration ||
                            Math.abs(duration - video.duration * 1000) > 2000) continue;
                        var legacy = item.videoDeliveryLegacyFields || item;
                        var urls = [legacy.browser_native_hd_url, legacy.playable_url_quality_hd,
                            legacy.browser_native_sd_url, legacy.playable_url];
                        var delivery = item.videoDeliveryResponseFragment?.videoDeliveryResponseResult;
                        (delivery?.progressive_urls || []).forEach(function (format) { urls.push(format.progressive_url); });
                        var alternatives = [];
                        for (var u = 0; u < urls.length && alternatives.length < 4; u++) {
                            try {
                                if (typeof urls[u] !== "string") continue;
                                var url = new URL(urls[u]);
                                if (url.protocol === "https:" && /(^|\.)fbcdn\.net$/.test(url.hostname) && !url.username && !url.password &&
                                    !alternatives.some(function (item) {return item.url === url.href;}))
                                    alternatives.push({ url: url.href, mimeType: "video/mp4" });
                            } catch (_) {}
                        }
                        if (alternatives.length) return {url: new URL(alternatives[0].url), sources: alternatives, id: identity.id};
                    }
                }
            }
            return null;
        }
        // Transfer a real network resource; never restyle, reparent or replace the page.
        async function describeStream() {
            var video = candidate;
            if (!video || !video.isConnected || video.error) return { error: "no_video" };
            if (video.mediaKeys || video.srcObject) return { error: "protected_stream" };
            if (!(video.currentSrc || video.src)) return { error: "no_video" };
            var source = video.currentSrc || video.src, captureId = currentId, url;
            try { url = new URL(source, document.baseURI); } catch (_) {}
            var sources = playerSources(video);
            var facebook = url?.protocol === "blob:" ? await facebookStream(video) : null;
            // A navigation, cancellation or React element reuse may happen at a yield.
            if (currentId !== captureId || candidate !== video || !video.isConnected || video.error ||
                (video.currentSrc || video.src) !== source ||
                (facebook?.id && facebookIdentity(video)?.id !== facebook.id)) return { error: "no_video" };
            if (video.mediaKeys || video.srcObject) return { error: "protected_stream" };
            if (facebook?.error) return { error: facebook.error };
            if (facebook?.url) sources = facebook.sources;
            if (sources.length) url = new URL(sources[0].url);
            if (!url || !/^https?:$/.test(url.protocol) || url.username || url.password) {
                return { error: "embedded_stream" };
            }
            nativeSession = { video: video, source: video.currentSrc || video.src, facebookId: facebook?.id,
                adapterUrl: !facebook && source.startsWith("blob:") ? url.href : null,
                position: video.ended ? 0 : video.currentTime || 0, paused: video.paused || video.ended };
            video.addEventListener("play", holdPageVideo);
            video.pause();
            var page = new URL(document.URL);
            // No cookies, authorization or URL fragments are exported or logged.
            var referrer = /^https?:$/.test(page.protocol) && !(page.protocol === "https:" && url.protocol === "http:") ? page.origin + "/" : "";
            if (page.protocol === "https:" && sources.some(function (item) {return item.url.startsWith("http:");})) referrer = "";
            if (document.referrerPolicy === "no-referrer") referrer = "";
            return { url: url.href, sources: sources.slice(0, 4), referrer: referrer, userAgent: navigator.userAgent,
                pos: nativeSession.position, paused: nativeSession.paused,
                loop: !!video.loop, muted: !!video.muted };
        }
        function returnStream(position, resume, paused) {
            var session = nativeSession;
            nativeSession = null;
            if (session) session.video.removeEventListener("play", holdPageVideo);
            if (!session || !session.video.isConnected ||
                    (session.video.currentSrc || session.video.src) !== session.source) return false;
            if (session.facebookId && facebookIdentity(session.video)?.id !== session.facebookId) return false;
            if (session.adapterUrl && !playerSources(session.video).some(function (item) {return item.url === session.adapterUrl;})) return false;
            var video = session.video;
            if (typeof position === "number" && isFinite(position) && position >= 0) {
                try { video.currentTime = isFinite(video.duration) ? Math.min(position, video.duration) : position; } catch (_) {}
            }
            if (resume && !(typeof paused === "boolean" ? paused : session.paused)) {
                resumeByUser(video);
                video.play().catch(function () {});
            } else suspendVideo(video);
            return true;
        }
        function holdPageVideo() {
            if (nativeSession) nativeSession.video.pause();
        }
        async function engineTakeover(paused, canPrompt) {
            var video = candidate, id = currentId;
            if (!video || !video.isConnected || video.error) return "none";
            // Browser fullscreen preserves MSE, cookies and licensed decoding in
            // Gecko. Only VIDEO enters the top layer; site containers stay intact.
            var wasPaused = typeof paused === "boolean" ? paused : nativeSession ? nativeSession.paused : video.paused;
            var source = video.currentSrc || video.src, facebookId = facebookIdentity(video)?.id;
            returnStream(null, false, true);
            var controls = video.controls;
            try {
                await video.requestFullscreen();
                if (currentId !== id || !video.isConnected || (video.currentSrc || video.src) !== source ||
                    (facebookId && facebookIdentity(video)?.id !== facebookId) || !isVideoFullscreen(video)) {
                    if (isVideoFullscreen(video)) await document.exitFullscreen();
                    return "cancelled";
                }
                active = video;
                engineMode = true;
                // Gecko's fullscreen dimensions override page CSS, but object-fit
                // does not. Prevent a site's cover/crop setting clipping the frame.
                var css = window.getComputedStyle(video);
                engineFit = { hadStyle: video.getAttribute("style") !== null, properties: [] };
                [["object-fit", "contain", css.objectFit], ["object-position", "50% 50%", css.objectPosition]].forEach(function (item) {
                    if (!item[2] || item[2] === item[1]) return;
                    engineFit.properties.push({ name: item[0], applied: item[1],
                        value: video.style.getPropertyValue(item[0]), priority: video.style.getPropertyPriority(item[0]) });
                    video.style.setProperty(item[0], item[1], "important");
                });
                engineSource = source;
                engineFacebookId = facebookId;
                hadControls = controls;
                video.controls = false;
                ready = true;
                if (wasPaused) suspendVideo(video);
                else { resumeByUser(video); video.play().catch(function () {}); }
                events.forEach(function (event) { video.addEventListener(event, pushState); });
                timer = setInterval(pushState, 500);
                send(snapshot({ t: "takeover", ok: true, fs: true, mode: "engine" }));
                return "ok";
            } catch (_) {
                video.controls = controls;
                if (canPrompt !== false && currentId === id && document.fullscreenEnabled !== false && video.isConnected) {
                    // Gecko does not propagate a toolbar gesture into the document.
                    // A real page click supplies activation without changing security prefs.
                    if (engineTrigger) engineTrigger.remove();
                    var button = document.createElement("button");
                    button.textContent = "Открыть плеер";
                    button.type = "button";
                    var rect = video.getBoundingClientRect();
                    button.style.cssText = "position:fixed!important;z-index:2147483647!important;" +
                        "background:#202332!important;color:white!important;border:2px solid white!important;" +
                        "border-radius:12px!important;padding:14px 20px!important;font:18px sans-serif!important;" +
                        "margin:0!important;transform:translate(-50%,-50%)!important;" +
                        "left:" + Math.max(100, Math.min(innerWidth - 100, rect.left + rect.width / 2)) + "px!important;" +
                        "top:" + Math.max(40, Math.min(innerHeight - 40, rect.top + rect.height / 2)) + "px!important;";
                    button.addEventListener("click", function (event) {
                        if (!event.isTrusted) return;
                        event.preventDefault(); event.stopPropagation();
                        button.remove(); engineTrigger = null;
                        if (currentId !== id || (video.currentSrc || video.src) !== source ||
                            (facebookId && facebookIdentity(video)?.id !== facebookId)) return;
                        engineTakeover(wasPaused, false).then(function (result) {
                            if (result !== "ok" && currentId === id) send({t:"takeover",ok:false,reason:"embedded_stream"});
                        });
                    });
                    document.documentElement.appendChild(button);
                    engineTrigger = button;
                    return "awaiting_gesture";
                }
                return "failed";
            }
        }
        function expandParents() {
            if (window === window.top) return Promise.resolve(true);
            return new Promise(function (resolve) {
                var timeout = setTimeout(function () { parentReply = null; resolve(false); }, 2000);
                parentReply = function (ok) { clearTimeout(timeout); resolve(ok); };
                window.parent.postMessage({ upgrid: "expand", requestId: currentId, token: currentToken }, "*");
            });
        }
        window.addEventListener("message", function (event) {
            var msg = event.data;
            if (!msg || currentId === null || msg.requestId !== currentId || msg.token !== currentToken) return;
            if (msg.upgrid === "expanded" && event.source === window.parent && parentReply) {
                var resolve = parentReply;
                parentReply = null;
                resolve(msg.ok === true);
            } else if (msg.upgrid === "expand") {
                var frame = findElements(document, "iframe,frame").find(function (item) { return item.contentWindow === event.source; });
                if (!frame) return;
                var id = currentId, key = currentToken;
                isolate(frame);
                expandParents().then(function (ok) {
                    if (id !== currentId) return;
                    event.source.postMessage({ upgrid: "expanded", requestId: id, token: key, ok: ok }, "*");
                });
            }
        });
        function takeover() {
            if (pending) return pending;
            if (ready) { send(snapshot({ t: "takeover", ok: true, fs: false })); return Promise.resolve("ok"); }
            if (!candidate || !candidate.isConnected || candidate.error) candidate = pickVideo();
            if (!candidate) return Promise.resolve("none");
            active = candidate;
            hadControls = active.controls;
            active.controls = false;
            isolate(active);
            var id = currentId;
            pending = expandParents().then(function (expanded) {
                if (currentId !== id || !active) return "cancelled";
                if (!expanded || !active.isConnected) { release(false); return "failed"; }
                ready = true;
                events.forEach(function (event) { active.addEventListener(event, pushState); });
                timer = setInterval(pushState, 750);
                send(snapshot({ t: "takeover", ok: true, fs: false }));
                return "ok";
            }).catch(function () { if (currentId === id) release(false); return "failed"; });
            return pending;
        }
        browser.runtime.onMessage.addListener(function (msg) {
            if (!msg || msg.requestId !== currentId || currentId === null) return;
            if (msg.cmd === "describe_stream") return Promise.resolve(describeStream());
            if (msg.cmd === "return_stream") {
                return Promise.resolve(returnStream(msg.pos, msg.resume === true, msg.paused) ? "ok" : "stale");
            }
            if (msg.cmd === "release") {
                returnStream(null, msg.resume !== false);
                release(!msg.silent);
                return Promise.resolve();
            }
            if (msg.cmd === "takeover") return takeover();
            if (msg.cmd === "engine_takeover") return engineTakeover(msg.paused);
            if (!active || !ready) return;
            switch (msg.cmd) {
                case "pause": suspendVideo(active); break;
                case "toggle":
                    if (active.paused || active.ended) { resumeByUser(active); active.play().catch(function () {}); } else suspendVideo(active);
                    break;
                case "seekBy":
                    var next = (active.currentTime || 0) + (msg.delta || 0);
                    active.currentTime = Math.max(0, isFinite(active.duration) ? Math.min(active.duration, next) : next);
                    break;
                case "seekTo":
                    if (isFinite(active.duration) && active.duration > 0) active.currentTime = active.duration * Math.max(0, Math.min(1, msg.frac || 0));
                    break;
                case "loop": active.loop = !active.loop; break;
            }
            pushState();
        });
        window.addEventListener("pagehide", function () { returnStream(null, false); release(true); });
        window.addEventListener("fullscreenchange", function () {
            if (engineMode && active && !isVideoFullscreen(active)) release(true);
        });
        window.__upgridPagePlayer = function (id, key) {
            if (currentId !== id) release(false);
            currentId = id;
            currentToken = key;
            candidate = pickVideo();
            if (!candidate) return Promise.resolve(null);
            var rect = candidate.getBoundingClientRect();
            return send({ t: "candidate", playing: !candidate.paused && !candidate.ended && candidate.readyState >= 2,
                area: rect.width * rect.height });
        };
    }
    return window.__upgridPagePlayer(requestId, token);
}
