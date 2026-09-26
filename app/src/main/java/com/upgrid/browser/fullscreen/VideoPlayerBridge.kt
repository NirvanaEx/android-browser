package com.upgrid.browser.fullscreen

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.Action
import mozilla.components.concept.engine.webextension.ActionHandler
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONObject

/**
 * Native half of the built-in video player. Bridges the topbar ▶ button and
 * the player screen to the bundled WebExtension. Fenix retains the original
 * media session in real Gecko fullscreen; Media3 is a bounded fallback.
 *
 * Two channels:
 *  - takeover trigger: [requestTakeover] sends an explicit native open command
 *    → background.js selects the video → player.js requests fullscreen;
 *    the native overlay supplies its controls.
 *  - state/commands: a native-messaging port ("upgridPlayer") between this
 *    class and background.js. The content script streams playback state
 *    (position/duration/paused/...) up; [sendCommand] sends play/seek/loop/
 *    release down. See player.js header for the message protocol.
 */
class VideoPlayerBridge(private val engine: Engine, private val storedAction: () -> (() -> Unit)? = { null }) {

    /**
     * Player events from the extension, always delivered on the main thread.
     * Message types: "takeover" (ok, fs + initial state), "state" (periodic
     * playback snapshot), "released" (page got its video back).
     */
    var onPlayerEvent: (JSONObject) -> Unit = {}
    var onDiagnostic: (String, Throwable?) -> Unit = { _, _ -> }

    /** Captured when the extension finishes install + announces its action. */
    private var browserActionOnClick: (() -> Unit)? = null

    /** Native-messaging port, prepared when the background script starts. */
    private var port: Port? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var openingAt = 0L

    fun markControlsDrawn() {
        if (openingAt > 0) Log.i(TAG, "controls_draw elapsedMs=${SystemClock.elapsedRealtime() - openingAt}")
    }

    fun setupAndInstall() {
        engine.installBuiltInWebExtension(
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
                // Built-in background messages/connections are queued by the
                // engine until this handler is registered.
                ext.registerBackgroundMessageHandler(PORT_NAME, object : MessageHandler {
                    override fun onPortConnected(port: Port) {
                        Log.i(TAG, "player port connected")
                        this@VideoPlayerBridge.port = port
                    }

                    override fun onPortDisconnected(port: Port) {
                        if (this@VideoPlayerBridge.port == port) {
                            this@VideoPlayerBridge.port = null
                            mainHandler.post { onPlayerEvent(JSONObject().put("t", "released")) }
                        }
                    }

                    override fun onPortMessage(message: Any, port: Port) {
                        val json = message as? JSONObject ?: return
                        if (json.optString("t") == "takeover" && json.optBoolean("ok") && openingAt > 0) {
                            val path = if (json.optString("path") == "cached") "cached" else "scan"
                            Log.i(TAG, "player_ready elapsedMs=${SystemClock.elapsedRealtime() - openingAt} pageMs=${json.optLong("pageMs")} path=$path")
                        }
                        if (json.optString("t") == "takeover" && !json.optBoolean("ok")) {
                            onDiagnostic("player_" + json.optString("reason", "failed"), null)
                        }
                        // A stream event contains a possibly signed URL. Never log its payload.
                        if (json.optString("t") != "state") Log.i(TAG, "event: ${json.optString("t")}")
                        mainHandler.post {
                            runCatching { onPlayerEvent(json) }.onFailure {
                                onDiagnostic("player_event", it)
                                sendCommand("release")
                            }
                        }
                    }
                })
            },
            onError = { t -> Log.w(TAG, "install failed", t); onDiagnostic("player_install", t) },
        )
    }

    /**
     * Take over the page's video. Caller MUST be inside a real Android input
     * event handler (e.g. button onClickListener). Gecko can still require a
     * direct page gesture after the page's fullscreen activation has expired.
     */
    fun requestTakeover(): Boolean {
        val connectedPort = port
        val click = browserActionOnClick ?: storedAction()
        if (connectedPort == null && click == null) {
            Log.w(TAG, "requestTakeover: extension not yet ready, dropping tap")
            onDiagnostic("player_not_ready", null)
            return false
        }
        return runCatching {
            openingAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "open_start")
            onPlayerEvent(JSONObject().put("t", "opening"))
            // Fenix also registers action delegates for installed extensions.
            // Recover its cached action if it arrived before our handler.
            // A ready native port remains the fallback during cold startup.
            // This does not grant page activation: requestFullscreen still uses
            // Gecko's normal permission and trusted-input checks.
            // The genuine extension action already identifies the target tab.
            if (click != null) click.invoke()
            else connectedPort?.postMessage(JSONObject().put("cmd", "open"))
        }
            .onFailure {
                onPlayerEvent(JSONObject().put("t", "released"))
                Log.w(TAG, "onClick threw", it); onDiagnostic("player_open", it)
            }
            .isSuccess
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
            .onFailure { Log.w(TAG, "sendCommand($cmd) failed", it); onDiagnostic("player_command", it) }
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
