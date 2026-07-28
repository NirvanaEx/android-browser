// background.js — relay between the Android side and the page-side player.
//
//   native (VideoPlayerBridge) ⇆ this page  : native-messaging port "upgridPlayer"
//   this page ⇆ player.js content script    : tabs.executeScript + tabs.sendMessage
//
// The browserAction click is the only entry point that carries a user-gesture
// token, and Mozilla deliberately preserves it through onClicked →
// tabs.executeScript — that's what lets player.js call requestFullscreen()
// and play() on a <video> the user never touched directly.
//
// Frame locking: the takeover script runs in ALL frames (videos often live in
// embed iframes). The first frame that reports a successful takeover becomes
// the controlled frame; any later frame that also grabbed a video is told to
// quietly let go. Commands and state are routed only to/from the locked frame.

var port = null;
var playerTabId = null;
var playerFrameId = null;
var locked = false;

function ensurePort() {
    if (port) return port;
    try {
        port = browser.runtime.connectNative("upgridPlayer");
        port.onMessage.addListener(function (msg) {
            // Command from the native overlay → controlled frame only.
            if (playerTabId === null || playerFrameId === null) return;
            browser.tabs.sendMessage(playerTabId, msg, { frameId: playerFrameId })
                .catch(function () {});
        });
        port.onDisconnect.addListener(function () { port = null; });
    } catch (e) {
        console.error("[upgrid-player] connectNative failed:", e && e.message);
        port = null;
    }
    return port;
}

function postToNative(msg) {
    var p = ensurePort();
    if (p) { try { p.postMessage(msg); } catch (e) {} }
}

// State/takeover reports from player.js → native port.
browser.runtime.onMessage.addListener(function (msg, sender) {
    if (!msg || !msg.t || !sender || !sender.tab) return;
    var tabId = sender.tab.id;
    var frameId = (sender.frameId !== undefined) ? sender.frameId : 0;

    if (msg.t === "takeover" && msg.ok) {
        if (locked && (tabId !== playerTabId || frameId !== playerFrameId)) {
            // A second frame also found a video after we locked on — release
            // it silently so two players don't fight over the state stream.
            browser.tabs.sendMessage(tabId, { cmd: "release", silent: true },
                { frameId: frameId }).catch(function () {});
            return;
        }
        locked = true;
        playerTabId = tabId;
        playerFrameId = frameId;
    } else {
        if (tabId !== playerTabId || frameId !== playerFrameId) return; // stray frame
        if (msg.t === "released") locked = false;
    }
    if (msg.t !== "state") {
        console.log("[upgrid-player] relay: " + JSON.stringify(msg) + " frame " + frameId);
    }
    postToNative(msg);
});

browser.browserAction.onClicked.addListener(function (tab) {
    if (!tab || tab.id === undefined) return;
    console.log("[upgrid-player] action clicked, tab " + tab.id);
    locked = false;
    playerTabId = tab.id;
    playerFrameId = null;
    ensurePort();
    // Injection is idempotent (player.js guards itself) and always re-runs
    // the takeover entry point with this click's gesture token.
    browser.tabs.executeScript(tab.id, { file: "player.js", allFrames: true })
        .then(function (results) {
            console.log("[upgrid-player] inject results: " + JSON.stringify(results));
            var any = results && results.some(function (r) { return r === "ok"; });
            if (!any) postToNative({ t: "takeover", ok: false });
        })
        .catch(function (e) {
            console.error("[upgrid-player] inject failed:", e && e.message);
            postToNative({ t: "takeover", ok: false });
        });
});

// The controlled document can go away without player.js ever getting to say
// so — a navigation tears the content script down mid-flight, and closing the
// tab takes the whole frame with it. Neither fires our "released" event, which
// used to strand the native overlay on top of a completely different page.
// Report the release ourselves so the native side always converges.
function dropIfControlled(tabId, reason) {
    if (!locked || tabId !== playerTabId) return;
    console.log("[upgrid-player] drop (" + reason + ") tab " + tabId);
    locked = false;
    playerTabId = null;
    playerFrameId = null;
    postToNative({ t: "released" });
}

browser.tabs.onUpdated.addListener(function (tabId, changeInfo) {
    // Only a committed load counts. Title/favicon/audible updates fire
    // constantly on video pages and must not kill an active takeover.
    if (changeInfo.status === "loading" && changeInfo.url) {
        dropIfControlled(tabId, "navigated");
    }
});

browser.tabs.onRemoved.addListener(function (tabId) {
    dropIfControlled(tabId, "tab closed");
});

console.log("[upgrid-player] background ready");
