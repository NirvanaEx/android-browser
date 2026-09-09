package org.mozilla.fenix.upgrid

import android.view.ViewGroup
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.UpgridViewFullscreenControlsBinding

/** Media3 first; browser-video fullscreen when the source must stay in Gecko. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
class UpgridPlayerFeature(
    private val fragment: Fragment,
    private val parent: ViewGroup,
    private val bridge: VideoPlayerBridge,
    private val onVisibilityChanged: (Boolean) -> Unit,
) : DefaultLifecycleObserver {
    private val activity = fragment.requireActivity()
    private var player: NativeVideoPlayer? = null
    private var messageDialog: AlertDialog? = null
    private var started = false
    private var engineControls: PlayerOverlayController? = null
    private var engineBinding: UpgridViewFullscreenControlsBinding? = null
    private var engineOrientation = activity.requestedOrientation
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { bridge.sendCommand("release"); hideEngine() }
    }
    private val listener: (JSONObject) -> Unit = { event ->
        when (event.optString("t")) {
            "stream" -> {
                if (!started) {
                    bridge.sendCommand("return_stream") {
                        put("requestId", event.optInt("requestId"))
                        put("resume", false)
                    }
                } else {
                    player?.close(false)
                    hideEngine()
                    var fallback = false
                    val next = NativeVideoPlayer(activity, event,
                        onClosed = { position, paused, resume ->
                            player = null
                            bridge.sendCommand(if (fallback && started) "engine_fallback" else "return_stream") {
                                put("requestId", event.optInt("requestId"))
                                put("pos", position)
                                put("paused", paused)
                                put("resume", resume)
                            }
                        },
                        onError = { code ->
                            bridge.onDiagnostic("player_$code", null)
                            fallback = true
                        },
                    )
                    player = next
                    next.show()
                }
            }
            "takeover" -> if (event.optBoolean("ok") && event.optString("mode") == "engine") {
                if (started) showEngine(event) else bridge.sendCommand("release")
            } else if (!event.optBoolean("ok") && started) {
                message(when (event.optString("reason")) {
                    "embedded_stream", "protected_stream" -> R.string.upgrid_player_native_unsupported
                    "no_video" -> R.string.upgrid_player_no_video
                    else -> R.string.upgrid_player_native_failed
                })
            }
            "state" -> engineControls?.renderState(event)
            "gesture_required" -> if (started) Toast.makeText(activity,
                R.string.upgrid_player_engine_tap, Toast.LENGTH_LONG).show()
            "released" -> { player?.close(false); player = null; hideEngine() }
        }
    }

    init {
        bridge.onPlayerEvent = listener
        // Safe for the empty-tab probe: no toolbar access during construction.
        fragment.viewLifecycleOwner.lifecycle.addObserver(this)
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, back)
    }

    private fun showEngine(state: JSONObject) {
        if (engineControls == null) {
            val binding = UpgridViewFullscreenControlsBinding.inflate(fragment.layoutInflater, parent, false)
            parent.addView(binding.root, ViewGroup.LayoutParams(-1, -1))
            engineBinding = binding
            engineControls = PlayerOverlayController(binding, bridge, { 5 }, activity.window,
                activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
                onExit = { bridge.sendCommand("release"); hideEngine() }, onPip = {},
                onRotate = {
                    activity.requestedOrientation = if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                })
            // Keep only working controls, with no permanent seek buttons.
            listOf(binding.fsPrev, binding.fsNext, binding.fsPlaylist, binding.fsHd,
                binding.fsPip, binding.fsLock).forEach { it.visibility = View.GONE }
        }
        engineOrientation = activity.requestedOrientation
        back.isEnabled = true
        onVisibilityChanged(true)
        engineBinding?.root?.bringToFront()
        engineControls?.setVisible(true)
        engineControls?.renderState(state)
    }

    private fun hideEngine() {
        if (engineControls?.isVisible != true) return
        engineControls?.setVisible(false)
        back.isEnabled = false
        activity.requestedOrientation = engineOrientation
        onVisibilityChanged(false)
    }

    private fun message(text: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        messageDialog?.dismiss()
        messageDialog = AlertDialog.Builder(activity).setTitle(R.string.upgrid_player_native_title)
            .setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onStart(owner: LifecycleOwner) { started = true }
    override fun onPause(owner: LifecycleOwner) {
        player?.pause()
        bridge.sendCommand("pause")
    }
    override fun onStop(owner: LifecycleOwner) {
        started = false
        messageDialog?.dismiss()
        messageDialog = null
        player?.close(false)
        bridge.sendCommand("suspend")
        hideEngine()
    }
    override fun onDestroy(owner: LifecycleOwner) {
        player?.close(false)
        player = null
        hideEngine()
        engineControls?.dispose()
        engineBinding?.root?.let { parent.removeView(it) }
        engineControls = null
        engineBinding = null
        if (bridge.onPlayerEvent === listener) {
            bridge.sendCommand("release")
            bridge.onPlayerEvent = {}
        }
    }
}
