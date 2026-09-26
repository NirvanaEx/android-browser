package org.mozilla.fenix.upgrid

import android.view.ViewGroup
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.View
import android.widget.Toast
import android.widget.ProgressBar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import android.view.Gravity
import androidx.core.view.doOnPreDraw
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.UpgridViewFullscreenControlsBinding

/** Retain the playing Gecko video; Media3 is a bounded fallback. */
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
    private var opening: ProgressBar? = null
    private var engineControls: PlayerOverlayController? = null
    private var engineBinding: UpgridViewFullscreenControlsBinding? = null
    private var engineOrientation = activity.requestedOrientation
    private var orientationHeld = false
    private var destroyed = false
    private var scaleMode = "contain"
    private var mirrorHorizontal = false
    private var mirrorVertical = false
    val isActive: Boolean get() = orientationHeld
    private val warmControls = Runnable { if (started && !destroyed) prepareEngineControls() }
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { exitToPage() }
    }
    private val listener: (JSONObject) -> Unit = { event ->
        if (event.optString("t") != "state" && event.optString("t") != "opening") hideOpening()
        when (event.optString("t")) {
            "opening" -> if (started && opening == null) {
                holdOrientation()
                back.isEnabled = true
                opening = ProgressBar(activity).also {
                    it.contentDescription = activity.getString(R.string.upgrid_player_open)
                    val size = (48 * activity.resources.displayMetrics.density).toInt()
                    parent.addView(it, CoordinatorLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER })
                }
            }
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
                back.isEnabled = false
                restoreOrientation()
                message(when (event.optString("reason")) {
                    "embedded_stream", "protected_stream" -> R.string.upgrid_player_native_unsupported
                    "no_video" -> R.string.upgrid_player_no_video
                    else -> R.string.upgrid_player_native_failed
                })
            }
            "state" -> engineControls?.renderState(event)
            "gesture_required" -> if (started) Toast.makeText(activity,
                R.string.upgrid_player_engine_tap, Toast.LENGTH_LONG).show()
            "released" -> { player?.close(false); player = null; hideEngine(); back.isEnabled = false; restoreOrientation() }
        }
    }

    init {
        bridge.onPlayerEvent = listener
        // Safe for the empty-tab probe: no toolbar access during construction.
        fragment.viewLifecycleOwner.lifecycle.addObserver(this)
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, back)
    }

    private fun prepareEngineControls() {
        if (engineControls == null) {
            val binding = UpgridViewFullscreenControlsBinding.inflate(fragment.layoutInflater, parent, false)
            binding.root.visibility = View.GONE
            parent.addView(binding.root, ViewGroup.LayoutParams(-1, -1))
            engineBinding = binding
            engineControls = PlayerOverlayController(binding, bridge, { 5 }, activity.window,
                activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
                onExit = { exitToPage() }, onPip = {},
                onRotate = {
                    activity.requestedOrientation = if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                })
            // Keep only working controls, with no permanent seek buttons.
            listOf(binding.fsPrev, binding.fsNext,
                binding.fsPip, binding.fsLock).forEach { it.visibility = View.GONE }
            binding.fsPlaylist.setImageResource(R.drawable.upgrid_ic_player_mirror)
            binding.fsPlaylist.contentDescription = activity.getString(R.string.upgrid_player_mirror)
            binding.fsPlaylist.setOnClickListener {
                messageDialog?.dismiss()
                messageDialog = AlertDialog.Builder(activity).setTitle(R.string.upgrid_player_mirror)
                    .setMultiChoiceItems(arrayOf(activity.getString(R.string.upgrid_player_mirror_horizontal),
                        activity.getString(R.string.upgrid_player_mirror_vertical)),
                        booleanArrayOf(mirrorHorizontal, mirrorVertical)) { _, index, checked ->
                        if (index == 0) mirrorHorizontal = checked else mirrorVertical = checked
                        bridge.sendCommand("mirror") {
                            put("horizontal", mirrorHorizontal)
                            put("vertical", mirrorVertical)
                        }
                    }.setPositiveButton(android.R.string.ok, null).show()
            }
            binding.fsRotate.contentDescription = activity.getString(R.string.upgrid_player_native_rotate)
            binding.fsHd.setImageResource(R.drawable.upgrid_ic_player_scale)
            binding.fsHd.contentDescription = activity.getString(R.string.upgrid_player_scale)
            binding.fsHd.setOnClickListener {
                val modes = arrayOf("contain", "cover", "fill")
                messageDialog?.dismiss()
                messageDialog = AlertDialog.Builder(activity).setTitle(R.string.upgrid_player_scale)
                    .setSingleChoiceItems(arrayOf(activity.getString(R.string.upgrid_player_scale_fit),
                        activity.getString(R.string.upgrid_player_scale_cover), activity.getString(R.string.upgrid_player_scale_stretch)),
                        modes.indexOf(scaleMode)) { picker, index ->
                        scaleMode = modes[index]
                        bridge.sendCommand("scale") { put("mode", scaleMode) }
                        picker.dismiss()
                    }.show()
            }
        }
    }

    private fun holdOrientation() {
        if (orientationHeld) return
        engineOrientation = activity.requestedOrientation
        orientationHeld = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    private fun restoreOrientation() {
        if (!orientationHeld) return
        orientationHeld = false
        activity.requestedOrientation = engineOrientation
    }

    private fun showEngine(state: JSONObject) {
        prepareEngineControls()
        holdOrientation()
        scaleMode = "contain"
        mirrorHorizontal = state.optBoolean("mirrorX")
        mirrorVertical = state.optBoolean("mirrorY")
        back.isEnabled = true
        onVisibilityChanged(true)
        engineBinding?.root?.bringToFront()
        engineControls?.setVisible(true)
        engineControls?.renderState(state)
        engineBinding?.root?.doOnPreDraw { bridge.markControlsDrawn() }
    }

    private fun hideEngine() {
        if (engineControls?.isVisible != true) return
        messageDialog?.dismiss()
        messageDialog = null
        engineControls?.setVisible(false)
        back.isEnabled = false
        onVisibilityChanged(false)
    }

    private fun exitToPage() {
        bridge.sendCommand("release") { put("resume", started) }
        hideOpening()
        back.isEnabled = false
        restoreOrientation()
        hideEngine()
    }

    private fun hideOpening() {
        opening?.let { parent.removeView(it) }
        opening = null
    }

    private fun message(text: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        messageDialog?.dismiss()
        messageDialog = AlertDialog.Builder(activity).setTitle(R.string.upgrid_player_native_title)
            .setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        parent.post(warmControls)
    }
    override fun onPause(owner: LifecycleOwner) {
        player?.pause()
        bridge.sendCommand("pause")
    }
    override fun onStop(owner: LifecycleOwner) {
        started = false
        back.isEnabled = false
        parent.removeCallbacks(warmControls)
        hideOpening()
        messageDialog?.dismiss()
        messageDialog = null
        player?.close(false)
        bridge.sendCommand("suspend")
        hideEngine()
        restoreOrientation()
    }
    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        back.isEnabled = false
        parent.removeCallbacks(warmControls)
        player?.close(false)
        player = null
        hideEngine()
        restoreOrientation()
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
