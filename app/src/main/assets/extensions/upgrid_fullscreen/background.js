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
            // "pauseAll" is the one command that isn't about the takeover: it
            // means the user left, so everything playing anywhere has to stop.
            // Broadcast rather than route — the native side knows nothing about
            // Gecko tab ids, and a tab that isn't on screen has no business
            // making noise regardless of which one it is.
            if (msg && msg.cmd === "pauseAll") {
                browser.tabs.query({}).then(function (tabs) {
                    tabs.forEach(function (t) {
                        browser.tabs.sendMessage(t.id, { cmd: "pauseAll" })
                            .catch(function () {});
                    });
                }).catch(function () {});
                return;
            }
            // Find and translate act on the page you are looking at, whichever
            // frame the player happens to be locked to.
            if (msg && PAGE_COMMANDS[msg.cmd]) {
                sendToPage(msg);
                return;
            }
            // Everything else is a player command → controlled frame only.
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

// Commands aimed at the page rather than at the player: find.js and
// translate.js both live in the top frame of whatever tab is on screen.
var PAGE_COMMANDS = {
    find: 1, findNext: 1, findPrev: 1, findClear: 1,
    translate: 1, untranslate: 1, translateState: 1,
    loginFill: 1,
};

/**
 * Deliver to the foreground tab's main frame.
 *
 * The active-tab filter needs the embedder to mark sessions active
 * (EngineSession.markActiveForWebExtensions, which MainActivity now does on
 * every selection change). If that hasn't happened yet — first paint, a tab
 * restored but never selected — the query comes back empty, and falling back
 * to every tab is right: the command is idempotent, the user is looking at one
 * of them, and the alternative is a button that silently does nothing.
 */
function sendToPage(msg) {
    browser.tabs.query({ active: true })
        // A rejected filter is the same situation as an empty answer: we don't
        // know which tab, so tell them all.
        .catch(function () { return []; })
        .then(function (tabs) {
            if (tabs && tabs.length) return tabs;
            return browser.tabs.query({});
        })
        .then(function (tabs) {
            (tabs || []).forEach(function (t) {
                browser.tabs.sendMessage(t.id, msg, { frameId: 0 })
                    .catch(function () {});
            });
        })
        .catch(function () {});
}

/**
 * Translate a batch of strings.
 *
 * Made here rather than in the content script because a content script's fetch
 * carries the page's origin and is refused by CORS; the background page holds
 * the extension's host permissions and isn't.
 *
 * The endpoint is the one Chrome's own translation uses. It answers with one
 * [translation, detectedLanguage] pair per `q`, in order, and needs no key.
 * POST rather than GET so a long paragraph can't overflow the URL.
 */
function translateTexts(texts, to) {
    var url = "https://translate.googleapis.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=" +
        encodeURIComponent(to || "en");
    var body = texts.map(function (t) { return "q=" + encodeURIComponent(t); }).join("&");
    return fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
        body: body,
    }).then(function (response) {
        if (!response.ok) throw new Error("http " + response.status);
        return response.json();
    });
}

// Media presence, per frame, per tab. observer.js reports every frame
// separately (videos live in iframes as often as not), so a tab has media if
// ANY of its frames does. Only the foreground tab is reported onward: the
// button this drives takes over the page you are looking at, and offering it
// for a video playing three tabs away would be a lie.
var mediaByTab = {};

function publishMedia() {
    // GeckoView only marks a tab "active" for the extension APIs if the
    // embedder tells it to; MainActivity.markSelectedTabActive does, but not
    // before the tab has an engine session. Until then "active" is false
    // everywhere and filtering on it would report silence forever, so fall
    // back to counting every tab — we pause playback on tab switch, so at most
    // one of them is making noise anyway.
    var anyActive = Object.keys(mediaByTab).some(function (id) {
        return mediaByTab[id].active;
    });
    var has = false;
    var playing = false;
    Object.keys(mediaByTab).forEach(function (tabId) {
        var tab = mediaByTab[tabId];
        if (anyActive && !tab.active) return;
        Object.keys(tab.frames).forEach(function (frameId) {
            var f = tab.frames[frameId];
            if (f.has) has = true;
            if (f.playing) playing = true;
        });
    });
    postToNative({ t: "media", has: has, playing: playing });
}

function forgetTab(tabId) {
    if (mediaByTab[tabId]) {
        delete mediaByTab[tabId];
        publishMedia();
    }
}

// State/takeover reports from player.js, media reports from observer.js.
browser.runtime.onMessage.addListener(function (msg, sender) {
    if (!msg || !msg.t || !sender || !sender.tab) return;
    var tabId = sender.tab.id;
    var frameId = (sender.frameId !== undefined) ? sender.frameId : 0;

    // Network on behalf of translate.js. Returning the promise is what makes
    // the content script's sendMessage(...).then() resolve with the answer.
    if (msg.t === "translateFetch") {
        return translateTexts(msg.texts || [], msg.to)
            .then(function (data) { return { ok: true, data: data }; })
            .catch(function (e) { return { ok: false, error: String(e && e.message) }; });
    }

    // Page-feature reports travel straight through: unlike player events they
    // carry no frame lock and mean nothing to the relay itself. "tap" is the
    // most straight-through of all — a single bit meaning "a link was
    // followed", from any frame of any tab.
    if (msg.t === "find" || msg.t === "translate" || msg.t === "login" || msg.t === "tap") {
        postToNative(msg);
        return;
    }

    if (msg.t === "media") {
        var entry = mediaByTab[tabId] || (mediaByTab[tabId] = { active: false, frames: {} });
        entry.active = sender.tab.active !== false;
        entry.frames[frameId] = { has: !!msg.has, playing: !!msg.playing };
        publishMedia();
        return;
    }

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
        forgetTab(tabId);
    }
});

browser.tabs.onRemoved.addListener(function (tabId) {
    dropIfControlled(tabId, "tab closed");
    forgetTab(tabId);
});

// A tab we still have media state for has navigated away from the page that
// reported it; the new document re-reports as soon as observer.js runs.
browser.tabs.onActivated.addListener(function (info) {
    Object.keys(mediaByTab).forEach(function (tabId) {
        mediaByTab[tabId].active = (String(info.tabId) === String(tabId));
    });
    publishMedia();
});

console.log("[upgrid-player] background ready");
