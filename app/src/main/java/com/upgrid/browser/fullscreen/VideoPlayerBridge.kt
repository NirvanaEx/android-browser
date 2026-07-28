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

    /** Native-messaging port; connected lazily by background.js on first tap. */
    private var port: Port? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun setupAndInstall() {
        components.engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { ext ->
                Log.i(TAG, "Extension installed: ${ext.id}")
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
                    }
                })
                // Must be registered before background.js calls connectNative —
                // guaranteed because the connect only happens on the first
                // browser_action tap, long after install completes.
                ext.registerBackgroundMessageHandler(PORT_NAME, object : MessageHandler {
                    override fun onPortConnected(port: Port) {
                        Log.i(TAG, "player port connected")
                        this@VideoPlayerBridge.port = port
                    }

                    override fun onPortDisconnected(port: Port) {
                        if (this@VideoPlayerBridge.port == port) {
                            this@VideoPlayerBridge.port = null
                        }
                    }

                    override fun onPortMessage(message: Any, port: Port) {
                        val json = message as? JSONObject ?: return
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
        if (click == null) {
            Log.w(TAG, "requestTakeover: extension not yet ready, dropping tap")
            return false
        }
        runCatching { click.invoke() }.onFailure { Log.w(TAG, "onClick threw", it) }
        return true
    }

    /**
     * Send a command to the controlled video: "toggle", "seekBy" (+delta),
     * "seekTo" (+frac), "loop", "release". No-op if the port isn't up — which
     * can only happen before the first successful takeover anyway.
     */
    fun sendCommand(cmd: String, configure: JSONObject.() -> Unit = {}) {
        val p = port
        if (p == null) {
            Log.w(TAG, "sendCommand($cmd): port not connected")
            return
        }
        runCatching { p.postMessage(JSONObject().put("cmd", cmd).apply(configure)) }
            .onFailure { Log.w(TAG, "sendCommand($cmd) failed", it) }
    }

    companion object {
        private const val TAG = "VideoPlayerBridge"
        const val EXTENSION_ID = "fullscreen@upgrid.local"
        const val EXTENSION_URL =
            "resource://android/assets/extensions/upgrid_fullscreen/"

        /** Must match the name background.js passes to connectNative(). */
        private const val PORT_NAME = "upgridPlayer"
    }
}
