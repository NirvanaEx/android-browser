// Relay between the native overlay and one captured page/frame.
var port = null;
var playerTabId = null;
var playerFrameId = null;
var pending = false;
var locked = false;
var attempt = 0;
var captureTimeout = null;
// The Fenix overlay enables the native Media3 screen. Legacy keeps its old UI.
var nativePlayerEnabled = false;
var enginePlayerPreferred = false;
var activeTabId = null;
var returningStream = null;
var videoHints = new Map();
var nativeOpenRequest = null;

function cancelNativeOpen() {
    if (nativeOpenRequest) clearTimeout(nativeOpenRequest.timeout);
    nativeOpenRequest = null;
}

function openFromNative() {
    if (nativeOpenRequest) return;
    // tab activation events already identify the target in the common case.
    if (activeTabId !== null) { beginTakeover({ id: activeTabId }); return; }
    var request = { attempt: attempt };
    nativeOpenRequest = request;
    function fail(reason) {
        if (nativeOpenRequest !== request || request.attempt !== attempt) return;
        cancelNativeOpen();
        postToNative({t:"takeover", ok:false, reason:reason});
    }
    request.timeout = setTimeout(function () { fail("player_failed"); }, 6000);
    // This command comes from the browser's toolbar tap through the connected
    // native port. It does not manufacture page activation: Gecko still decides
    // whether the selected video may enter fullscreen.
    browser.tabs.query({active:true, currentWindow:true}).then(function (tabs) {
        if (nativeOpenRequest !== request || request.attempt !== attempt) return;
        var tab = (tabs || []).find(function (item) { return Number.isInteger(item.id); });
        if (!tab) { fail("no_video"); return; }
        cancelNativeOpen();
        beginTakeover(tab);
    }).catch(function () { fail("player_failed"); });
}

function finishEngineResult(requestId, result, allowNative) {
    if (requestId !== attempt || result === "ok") return;
    if (result === "awaiting_gesture") {
        clearTimeout(captureTimeout);
        captureTimeout = setTimeout(function () { if (requestId === attempt) resetPlayer(true); }, 30_000);
        // A bound direct HTTP video can open in the native fallback without
        // asking the user to find and tap it again. MSE/DRM stays in Gecko.
        if (allowNative && nativePlayerEnabled) return nativeTakeover(requestId, false, true);
        postToNative({ t: "gesture_required" });
        return;
    }
    if (allowNative && result !== "stale" && result !== "cancelled") return nativeTakeover(requestId, false);
    resetPlayer(false);
    postToNative({ t: "takeover", ok: false, reason: "embedded_stream" });
}

function engineFallback(requestId, position, paused, allowNative) {
    if (requestId !== attempt || playerTabId === null || playerFrameId === null) return;
    var tabId = playerTabId, frameId = playerFrameId;
    // Return position without starting page audio; the engine then owns playback.
    var restore = typeof position === "number" ? sendToFrame(tabId, frameId,
        { cmd: "return_stream", pos: position, paused: paused, resume: false }, requestId) : Promise.resolve("ok");
    return restore.then(function (restored) {
        if (requestId !== attempt) return;
        if (restored !== "ok") return "stale";
        return sendToFrame(tabId, frameId, { cmd: "engine_takeover", paused: paused }, requestId);
    }).then(function (result) { return finishEngineResult(requestId, result, allowNative); });
}

function nativeTakeover(requestId, allowEngine, keepGesture) {
    var tabId = playerTabId, frameId = playerFrameId;
    return sendToFrame(tabId, frameId, { cmd: "describe_stream", directOnly:keepGesture === true }, requestId).then(function (stream) {
        if (requestId !== attempt) {
            sendToFrame(tabId, frameId, { cmd: "release", silent: true, resume: false }, requestId);
            return;
        }
        if (!stream || stream.error || !/^https?:\/\//i.test(stream.url || "")) {
            if (keepGesture) { postToNative({t:"gesture_required"}); return; }
            if (allowEngine && stream && ["embedded_stream", "protected_stream"].includes(stream.error)) {
                return engineFallback(requestId);
            }
            resetPlayer(false);
            postToNative({ t: "takeover", ok: false, reason: stream && stream.error || "player_failed" });
            return;
        }
        locked = true;
        pending = false;
        clearTimeout(captureTimeout);
        captureTimeout = null;
        postToNative(Object.assign({}, stream, { t: "stream", requestId: requestId }));
    });
}

function sendToFrame(tabId, frameId, message, requestId) {
    var options = frameId === null ? {} : { frameId: frameId };
    message = Object.assign({}, message, { requestId: requestId === undefined ? attempt : requestId });
    return browser.tabs.sendMessage(tabId, message, options).catch(function () {});
}

function resetPlayer(report, resume) {
    cancelNativeOpen();
    var oldTab = playerTabId;
    var oldAttempt = attempt;
    clearTimeout(captureTimeout);
    captureTimeout = null;
    attempt++;
    playerTabId = null;
    playerFrameId = null;
    pending = false;
    locked = false;
    // Broadcast cancellation even before a winning frame has been selected.
    if (oldTab !== null) sendToFrame(oldTab, null, { cmd: "release", silent: true, resume: resume === true }, oldAttempt);
    if (report) postToNative({ t: "released" });
}

function ensurePort() {
    if (port) return port;
    try {
        port = browser.runtime.connectNative("upgridPlayer");
        port.onMessage.addListener(function (msg) {
            if (!msg || !msg.cmd) return;
            if (msg.cmd === "open") { openFromNative(); return; }
            if (msg.cmd === "suspend") {
                // Native close precedes lifecycle cancellation on this port.
                // Pause immediately, but let its in-flight position return finish.
                if (returningStream && returningStream.requestId === attempt) {
                    sendToFrame(returningStream.tabId, null, { cmd: "suspend_page" });
                    return;
                }
                resetPlayer(true);
                browser.tabs.query({ active: true, currentWindow: true }).then(function (tabs) {
                    tabs.forEach(function (tab) { sendToFrame(tab.id, null, { cmd: "suspend_page" }); });
                }).catch(function () {});
                return;
            }
            if (msg.cmd === "engine_fallback") {
                if (locked && msg.requestId === attempt) engineFallback(attempt, msg.pos, msg.paused);
                return;
            }
            if (msg.cmd === "return_stream") {
                if (!locked || msg.requestId !== attempt) return;
                var id = attempt;
                var flight = { requestId: id, tabId: playerTabId };
                returningStream = flight;
                sendToFrame(playerTabId, playerFrameId, msg).then(function () {
                    if (returningStream === flight) returningStream = null;
                    if (id === attempt) resetPlayer(true);
                });
                return;
            }
            if (msg.cmd === "release") {
                if (returningStream && returningStream.requestId === attempt) return;
                resetPlayer(true, msg.resume === true); return;
            }
            if (!locked) return;
            sendToFrame(playerTabId, playerFrameId, msg);
        });
        port.onDisconnect.addListener(function () {
            port = null;
            resetPlayer(false);
        });
    } catch (e) {
        port = null;
    }
    return port;
}

function postToNative(msg) {
    var p = ensurePort();
    if (p) { try { p.postMessage(msg); } catch (e) {} }
}

browser.runtime.onMessage.addListener(function (msg, sender) {
    if (!msg || !msg.t || !sender || !sender.tab) return;
    var tabId = sender.tab.id;
    var frameId = sender.frameId === undefined ? 0 : sender.frameId;
    if (msg.t === "video_hint") {
        var hintId = tabId + ":" + frameId;
        videoHints.delete(hintId);
        if (typeof msg.key === "string" && msg.key.length <= 100 && Number.isFinite(msg.area) && msg.area > 0) {
            videoHints.set(hintId, {tabId:tabId, frameId:frameId, key:msg.key, playing:msg.playing === true,
                audible:msg.audible === true, failed:msg.failed === true, area:msg.area});
            if (videoHints.size > 64) videoHints.delete(videoHints.keys().next().value);
        }
        return;
    }
    if (msg.t === "site_fullscreen" && enginePlayerPreferred && typeof msg.key === "string" && msg.key.length <= 100) {
        var epoch = attempt;
        browser.tabs.query({active:true, currentWindow:true}).then(function (tabs) {
            if (attempt !== epoch || !(tabs || []).some(tab => tab.id === tabId)) return;
            if (locked) return;
            cancelNativeOpen();
            clearTimeout(captureTimeout);
            if (playerTabId !== null) sendToFrame(playerTabId, null, {cmd:"release", silent:true, resume:true}, attempt);
            playerTabId = tabId;
            playerFrameId = frameId;
            pending = true;
            var id = ++attempt;
            postToNative({t:"opening", origin:"site"});
            captureTimeout = setTimeout(function () {
                if (attempt === id && !locked) resetPlayer(true);
            }, 6000);
            sendToFrame(tabId, frameId, {cmd:"adopt_fullscreen", key:msg.key}, id)
                .then(result => finishEngineResult(id, result, false));
        }).catch(function () {});
        return;
    }
    if (msg.t === "candidate") {
        if (!pending || msg.requestId !== attempt || tabId !== playerTabId) return Promise.resolve(null);
        return Promise.resolve({ frameId: frameId, playing: msg.playing === true, audible:msg.audible === true, failed:msg.failed === true, area: Number(msg.area) || 0 });
    }
    if (msg.t === "takeover" && !msg.ok && msg.requestId === attempt && tabId === playerTabId &&
        frameId === playerFrameId && (pending || locked)) {
        resetPlayer(false);
        postToNative({ t: "takeover", ok: false, reason: "embedded_stream" });
        return;
    }
    if (msg.t === "takeover" && msg.ok) {
        if (msg.requestId !== attempt || tabId !== playerTabId || (!pending && !locked) ||
                frameId !== playerFrameId) {
            sendToFrame(tabId, frameId, { cmd: "release", silent: true }, msg.requestId);
            return;
        }
        locked = true;
        pending = false;
        clearTimeout(captureTimeout);
        captureTimeout = null;
        playerFrameId = frameId;
    } else {
        if (msg.requestId !== attempt || !locked || tabId !== playerTabId || frameId !== playerFrameId) return;
        if (msg.t === "released") { resetPlayer(true); return; }
    }
    postToNative(msg);
});

function beginTakeover(tab) {
    if (!tab || tab.id === undefined) return;
    cancelNativeOpen();
    if (playerTabId === tab.id && (pending || locked)) return;
    if (playerTabId !== null) resetPlayer(true);
    var currentAttempt = ++attempt;
    playerTabId = tab.id;
    playerFrameId = null;
    pending = true;
    locked = false;
    ensurePort();
    captureTimeout = setTimeout(function () {
        if (currentAttempt !== attempt || locked) return;
        resetPlayer(false);
        postToNative({ t: "takeover", ok: false, reason: "player_failed" });
    }, 6000);
    var token = String(currentAttempt) + "-" + Math.random().toString(36).slice(2);
    function discover() { return browser.tabs.executeScript(tab.id, {
        code: "(" + upgridPlayerMain.toString() + ")(" + currentAttempt + "," + JSON.stringify(token) + ");",
        allFrames: true,
        matchAboutBlank: true,
    })
        .then(function (results) {
            if (currentAttempt !== attempt || locked) return;
            var candidates = (results || []).filter(function (item) { return item && Number.isInteger(item.frameId) && item.area > 0; });
            candidates.sort(function (a, b) { return Number(b.playing) - Number(a.playing) || Number(a.failed) - Number(b.failed) || Number(b.audible) - Number(a.audible) || b.area - a.area || a.frameId - b.frameId; });
            if (!candidates.length) {
                resetPlayer(false);
                postToNative({ t: "takeover", ok: false, reason: "no_video" });
                return;
            }
            playerFrameId = candidates[0].frameId;
            if (enginePlayerPreferred) return engineFallback(currentAttempt, undefined, undefined, true);
            if (nativePlayerEnabled) {
                return nativeTakeover(currentAttempt, true);
            }
            return sendToFrame(tab.id, playerFrameId, { cmd: "takeover" }, currentAttempt).then(function (result) {
                if (currentAttempt !== attempt || locked || result === "ok") return;
                resetPlayer(false);
                postToNative({ t: "takeover", ok: false, reason: "player_failed" });
            });
        })
        .catch(function () {
            if (currentAttempt !== attempt || locked) return;
            resetPlayer(false);
            postToNative({ t: "takeover", ok: false, reason: "no_video" });
        }); }
    var hints = Array.from(videoHints.values()).filter(hint => hint.tabId === tab.id);
    hints.sort((a, b) => Number(b.playing) - Number(a.playing) || Number(a.failed) - Number(b.failed) || Number(b.audible) - Number(a.audible) || b.area - a.area || a.frameId - b.frameId);
    if (enginePlayerPreferred && hints.length) {
        var hint = hints[0];
        playerFrameId = hint.frameId;
        sendToFrame(tab.id, hint.frameId, {cmd:"fast_takeover", key:hint.key, token:token}, currentAttempt)
            .then(function (result) {
                if (currentAttempt !== attempt || locked) return;
                if (result === "miss" || result === undefined) {
                    videoHints.delete(tab.id + ":" + hint.frameId);
                    playerFrameId = null;
                    return discover();
                }
                return finishEngineResult(currentAttempt, result, true);
            });
    } else discover();
}

browser.browserAction.onClicked.addListener(beginTakeover);

browser.tabs.onRemoved.addListener(function (tabId) {
    if (activeTabId === tabId) activeTabId = null;
    for (var [key, hint] of videoHints) if (hint.tabId === tabId) videoHints.delete(key);
    if (tabId === playerTabId) resetPlayer(true);
});
browser.tabs.onUpdated.addListener(function (tabId, change) {
    if (change.status === "loading") for (var [key, hint] of videoHints) if (hint.tabId === tabId) videoHints.delete(key);
    if (tabId === playerTabId && change.status === "loading") resetPlayer(true);
});
browser.tabs.onActivated.addListener(function (info) {
    cancelNativeOpen();
    if (activeTabId !== null && info.tabId !== activeTabId)
        sendToFrame(activeTabId, null, { cmd: "suspend_page" });
    activeTabId = info.tabId;
    if (playerTabId !== null && info.tabId !== playerTabId) resetPlayer(true);
});

// The built-in extension API queues this connection until Android registers its
// handler. Prepare transport before the first gesture; never open video here.
ensurePort();
