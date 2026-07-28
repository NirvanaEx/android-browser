package com.upgrid.browser.fullscreen

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.upgrid.browser.BrowserComponents
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.Action
import mozilla.components.concept.engine.webextension.ActionHandler
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONObject

/**
 * Native half of the built-in video player. Bridges the topbar ▶ button and
 * the fullscreen overlay to the bundled player WebExtension.
 *
 * Why a WebExtension at all: Gecko refuses `video.requestFullscreen()` (and
 * autoplaying `play()`) unless it can prove a user-gesture token is in scope.
 * A `loadUrl("javascript:...")` injection from native code carries no gesture
 * and silently fails. The one path that DOES preserve the gesture is
 * `browser_action.onClicked` → `tabs.executeScript` — Mozilla wires the
 * activation through both hops on purpose so toolbar actions can fullscreen.
 *
 * Two channels:
 *  - takeover trigger: [requestTakeover] fires the extension's browser_action
 *    (gesture in) → background.js injects player.js → the page's <video> is
 *    stripped of its controls and fullscreened — OUR overlay becomes the UI.
 *  - state/commands: a native-messaging port ("upgridPlayer") between this
 *    class and background.js. The content script streams playback state
 *    (position/duration/paused/...) up; [sendCommand] sends play/seek/loop/
 *    release down. See player.js header for the message protocol.
 */
class VideoPlayerBridge(private val components: BrowserComponents) {

    /**
     * Player events from the extension, always delivered on the main thread.
     * Message types: "takeover" (ok, fs + initial state), "state" (periodic
     * playback snapshot), "released" (page got its video back).
     */
    var onPlayerEvent: (JSONObject) -> Unit = {}

    /** Captured when the extension finishes install + announces its action. */
    private var browserActionOnClick: (() -> Unit)? = null

    /**
     * A takeover asked for before the browser action was announced.
     *
     * The announcement is asynchronous and races app start, so a tap in the
     * first seconds used to be dropped with an apologetic toast while a video
     * was visibly playing. Holding the request and firing it the moment the
     * action lands removes the failure instead of explaining it. Safe to defer:
     * the user-gesture token Gecko needs comes from the browser-action click
     * itself, not from the Android touch event that asked for it.
     */
    private var takeoverPending = false

    /** The installed extension, kept so the action handler can be re-armed. */
    private var extension: WebExtension? = null

    /**
     * Whether the foreground page has a media element, and whether one is
     * playing. Maintained by observer.js, which runs on every page — see the
     * comment at the top of that file for why this isn't derived from
     * `mediaSessionState`.
     */
    var hasMedia: Boolean = false
        private set

    var isMediaPlaying: Boolean = false
        private set

    /** Called on the main thread whenever [hasMedia]/[isMediaPlaying] change. */
    var onMediaStateChanged: () -> Unit = {}

    /**
     * Reports from the page features that share this channel — find in page
     * (`t:"find"`) and translation (`t:"translate"`). Delivered on the main
     * thread. They have nothing to do with the player; the port is simply the
     * one pipe we have into the content scripts.
     */
    var onPageEvent: (JSONObject) -> Unit = {}

    /** Native-messaging port; connected lazily by background.js on first tap. */
    private var port: Port? = null

    /**
     * Commands issued before the port came up.
     *
     * background.js connects on its first outbound message, which for a normal
     * page is observer.js's opening media report — a second or so after load.
     * A user who opens find-in-page faster than that used to get silence.
     * Bounded because a queue that outlives its usefulness is just a delayed
     * surprise; the cap is far above the two or three commands a real burst
     * produces.
     */
    private val queued = ArrayDeque<JSONObject>()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun setupAndInstall() {
        components.engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { ext ->
                Log.i(TAG, "Extension installed: ${ext.id}")
                extension = ext
                ext.registerActionHandler(object : ActionHandler {
                    override fun onBrowserAction(
                        extension: WebExtension,
                        session: EngineSession?,
                        action: Action,
                    ) {
                        // Only the global default action (session=null) matters;
                        // per-tab overrides would land here with a session.
                        if (session != null) return
                        Log.i(TAG, "browser_action defined; onClick captured")
                        browserActionOnClick = action.onClick
                        if (takeoverPending) {
                            takeoverPending = false
                            Log.i(TAG, "firing takeover queued before the action existed")
                            mainHandler.post { runCatching { action.onClick.invoke() } }
                        }
                    }
                })
                // Must be registered before background.js calls connectNative —
                // guaranteed because the connect only happens on the first
                // browser_action tap, long after install completes.
                ext.registerBackgroundMessageHandler(PORT_NAME, object : MessageHandler {
                    override fun onPortConnected(port: Port) {
                        Log.i(TAG, "player port connected")
                        this@VideoPlayerBridge.port = port
                        mainHandler.post { flushQueue() }
                    }

                    override fun onPortDisconnected(port: Port) {
                        if (this@VideoPlayerBridge.port == port) {
                            this@VideoPlayerBridge.port = null
                        }
                    }

                    override fun onPortMessage(message: Any, port: Port) {
                        val json = message as? JSONObject ?: return
                        // Media reports are chatter about the page, not about
                        // the player, and they arrive whether or not anything
                        // was ever taken over — so they're handled here rather
                        // than pushed at an overlay that may not exist.
                        if (json.optString("t") == "media") {
                            mainHandler.post { updateMediaState(json) }
                            return
                        }
                        val type = json.optString("t")
                        if (type == "find" || type == "translate" || type == "login") {
                            mainHandler.post { runCatching { onPageEvent(json) } }
                            return
                        }
                        if (json.optString("t") != "state") Log.i(TAG, "event: $json")
                        mainHandler.post { runCatching { onPlayerEvent(json) } }
                    }
                })
            },
            onError = { t -> Log.w(TAG, "install failed", t) },
        )
    }

    /**
     * True once the extension is installed and has announced its browser
     * action — i.e. [requestTakeover] has a gesture path to fire. False for a
     * beat after cold start (install + action announcement are async).
     */
    val isReady: Boolean get() = browserActionOnClick != null

    /**
     * Take over the page's video. Caller MUST be inside a real Android input
     * event handler (e.g. button onClickListener) — that's what supplies the
     * gesture token that propagates through to the page.
     *
     * Returns false if the extension isn't wired up yet, so the caller can say
     * so instead of leaving the user tapping a button that does nothing.
     */
    fun requestTakeover(): Boolean {
        val click = browserActionOnClick
        if (click != null) {
            runCatching { click.invoke() }.onFailure { Log.w(TAG, "onClick threw", it) }
            return true
        }

        // Not announced yet. Remember the request, and re-arm the delegate in
        // case the announcement arrived in the window before we registered for
        // it — the two are asynchronous and neither waits for the other.
        Log.w(TAG, "requestTakeover: action not announced yet, queueing")
        takeoverPending = true
        extension?.let { ext ->
            runCatching {
                ext.registerActionHandler(object : ActionHandler {
                    override fun onBrowserAction(
                        extension: WebExtension,
                        session: EngineSession?,
                        action: Action,
                    ) {
                        if (session != null) return
                        browserActionOnClick = action.onClick
                        if (takeoverPending) {
                            takeoverPending = false
                            mainHandler.post { runCatching { action.onClick.invoke() } }
                        }
                    }
                })
            }
        }
        return false
    }

    /**
     * Fold one media report from observer.js into [hasMedia]/[isMediaPlaying].
     * Main thread; notifies only on an actual change, because the reports
     * arrive per frame and most of them say the same thing as the last one.
     */
    private fun updateMediaState(json: JSONObject) {
        val has = json.optBoolean("has", false)
        val playing = json.optBoolean("playing", false)
        if (has == hasMedia && playing == isMediaPlaying) return
        hasMedia = has
        isMediaPlaying = playing
        runCatching { onMediaStateChanged() }
    }

    /**
     * Pause every media element in every tab.
     *
     * Broadcast rather than aimed: this is called when the user leaves, and a
     * tab that isn't on screen shouldn't be playing whichever one it is. The
     * native side has no way to name a Gecko tab anyway.
     */
    fun pauseAllMedia() {
        // Not sendCommand(): that logs a warning when the port is down, and
        // the port being down here just means no page ever had a video.
        val p = port ?: return
        runCatching { p.postMessage(JSONObject().put("cmd", "pauseAll")) }
            .onFailure { Log.w(TAG, "pauseAllMedia failed", it) }
    }

    /**
     * Send a command to the controlled video: "toggle", "seekBy" (+delta),
     * "seekTo" (+frac), "loop", "release". No-op if the port isn't up — which
     * can only happen before the first successful takeover anyway.
     */
    fun sendCommand(cmd: String, configure: JSONObject.() -> Unit = {}) {
        val message = JSONObject().put("cmd", cmd).apply(configure)
        val p = port
        if (p == null) {
            Log.w(TAG, "sendCommand($cmd): port not connected, queued")
            if (queued.size >= MAX_QUEUED) queued.removeFirst()
            queued.addLast(message)
            return
        }
        runCatching { p.postMessage(message) }
            .onFailure { Log.w(TAG, "sendCommand($cmd) failed", it) }
    }

    /**
     * Send to the page rather than to the controlled video: find in page and
     * translation. Same pipe, different destination — background.js routes on
     * the verb (see PAGE_COMMANDS there).
     */
    fun sendPageCommand(cmd: String, configure: JSONObject.() -> Unit = {}) =
        sendCommand(cmd, configure)

    private fun flushQueue() {
        val p = port ?: return
        while (queued.isNotEmpty()) {
            val message = queued.removeFirst()
            runCatching { p.postMessage(message) }
                .onFailure { Log.w(TAG, "queued command failed", it) }
        }
    }

    companion object {
        private const val TAG = "VideoPlayerBridge"
        const val EXTENSION_ID = "fullscreen@upgrid.local"
        const val EXTENSION_URL =
            "resource://android/assets/extensions/upgrid_fullscreen/"

        /** Must match the name background.js passes to connectNative(). */
        private const val PORT_NAME = "upgridPlayer"

        /** Commands held while the port is down. See [queued]. */
        private const val MAX_QUEUED = 8
    }
}
