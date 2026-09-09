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
var activeTabId = null;
var returningStream = null;

function engineFallback(requestId, position, paused) {
    if (requestId !== attempt || playerTabId === null || playerFrameId === null) return;
    var tabId = playerTabId, frameId = playerFrameId;
    // Return position without starting page audio; the engine then owns playback.
    var restore = typeof position === "number" ? sendToFrame(tabId, frameId,
        { cmd: "return_stream", pos: position, paused: paused, resume: false }, requestId) : Promise.resolve("ok");
    return restore.then(function (restored) {
        if (requestId !== attempt) return;
        if (restored !== "ok") return "stale";
        return sendToFrame(tabId, frameId, { cmd: "engine_takeover", paused: paused }, requestId);
    }).then(function (result) {
        if (requestId !== attempt || result === "ok") return;
        if (result === "awaiting_gesture") {
            clearTimeout(captureTimeout);
            captureTimeout = setTimeout(function () { if (requestId === attempt) resetPlayer(true); }, 30_000);
            postToNative({ t: "gesture_required" });
            return;
        }
        resetPlayer(false);
        postToNative({ t: "takeover", ok: false, reason: "embedded_stream" });
    });
}

function sendToFrame(tabId, frameId, message, requestId) {
    var options = frameId === null ? {} : { frameId: frameId };
    message = Object.assign({}, message, { requestId: requestId === undefined ? attempt : requestId });
    return browser.tabs.sendMessage(tabId, message, options).catch(function () {});
}

function resetPlayer(report, resume) {
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
                resetPlayer(true); return;
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
    if (msg.t === "candidate") {
        if (!pending || msg.requestId !== attempt || tabId !== playerTabId) return Promise.resolve(null);
        return Promise.resolve({ frameId: frameId, playing: msg.playing === true, area: Number(msg.area) || 0 });
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

browser.browserAction.onClicked.addListener(function (tab) {
    if (!tab || tab.id === undefined) return;
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
    browser.tabs.executeScript(tab.id, {
        code: "(" + upgridPlayerMain.toString() + ")(" + currentAttempt + "," + JSON.stringify(token) + ");",
        allFrames: true,
        matchAboutBlank: true,
    })
        .then(function (results) {
            if (currentAttempt !== attempt || locked) return;
            var candidates = (results || []).filter(function (item) { return item && Number.isInteger(item.frameId) && item.area > 0; });
            candidates.sort(function (a, b) { return Number(b.playing) - Number(a.playing) || b.area - a.area || a.frameId - b.frameId; });
            if (!candidates.length) {
                resetPlayer(false);
                postToNative({ t: "takeover", ok: false, reason: "no_video" });
                return;
            }
            playerFrameId = candidates[0].frameId;
            if (nativePlayerEnabled) {
                return sendToFrame(tab.id, playerFrameId, { cmd: "describe_stream" }, currentAttempt).then(function (stream) {
                    if (currentAttempt !== attempt) {
                        sendToFrame(tab.id, candidates[0].frameId, { cmd: "release", silent: true }, currentAttempt);
                        return;
                    }
                    if (!stream || stream.error || !/^https?:\/\//i.test(stream.url || "")) {
                        if (stream && ["embedded_stream", "protected_stream"].includes(stream.error)) {
                            return engineFallback(currentAttempt);
                        }
                        resetPlayer(false);
                        postToNative({ t: "takeover", ok: false, reason: stream && stream.error || "player_failed" });
                        return;
                    }
                    locked = true;
                    pending = false;
                    clearTimeout(captureTimeout);
                    captureTimeout = null;
                    postToNative(Object.assign({}, stream, { t: "stream", requestId: currentAttempt }));
                });
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
        });
});

browser.tabs.onRemoved.addListener(function (tabId) {
    if (tabId === playerTabId) resetPlayer(true);
});
browser.tabs.onUpdated.addListener(function (tabId, change) {
    if (tabId === playerTabId && change.status === "loading") resetPlayer(true);
});
browser.tabs.onActivated.addListener(function (info) {
    if (activeTabId !== null && info.tabId !== activeTabId)
        sendToFrame(activeTabId, null, { cmd: "suspend_page" });
    activeTabId = info.tabId;
    if (playerTabId !== null && info.tabId !== playerTabId) resetPlayer(true);
});
