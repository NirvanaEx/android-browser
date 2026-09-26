package com.upgrid.browser.fullscreen

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ViewFullscreenControlsBinding
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns the fullscreen player overlay: buttons, the seek bar, and the gesture
 * layer. MainActivity only flips outer visibility ([setVisible]) and feeds
 * state snapshots from [VideoPlayerBridge] into [renderState]; everything
 * else — including talking back to the page through the bridge — lives here.
 *
 * Gesture map (the standard mobile-player vocabulary):
 *  - single tap          → toggle control bars
 *  - double tap left     → seek back by the configured step
 *  - double tap right    → seek forward            — " —
 *  - vertical drag right → system media volume
 *  - vertical drag left  → screen brightness (this window only)
 */
class PlayerOverlayController(
    private val binding: ViewFullscreenControlsBinding,
    private val bridge: VideoPlayerBridge,
    private val seekSeconds: () -> Int,
    private val window: Window,
    private val audioManager: AudioManager,
    private val onExit: () -> Unit,
    private val onPip: () -> Unit,
    private val onRotate: () -> Unit,
) {

    private var barsHidden = false

    /** True while the user is dragging the seek bar — state ticks must not
     *  yank the thumb out from under their finger. */
    private var userSeeking = false

    /** Last duration reported by the page, seconds. Drives the drag-preview
     *  time label; the page itself resolves fractions on real seeks. */
    private var durationSec = 0.0
    private var videoWidth = 0.0
    private var videoHeight = 0.0
    private var scaleMode = "contain"
    private val positionControls = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        positionBottomBar()
    }

    private enum class Drag { NONE, VOLUME, BRIGHTNESS }
    private var drag = Drag.NONE
    private var dragStartVolume = 0
    private var dragStartBrightness = 0.5f

    private val touchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop

    private val hideFlashLeft = Runnable { binding.fsSeekFlashLeft.isVisible = false }
    private val hideFlashRight = Runnable { binding.fsSeekFlashRight.isVisible = false }
    private val hideIndicator = Runnable { binding.fsGestureIndicator.isVisible = false }
    private val originalBrightness = window.attributes.screenBrightness

    init {
        wireButtons()
        wireSeekBar()
        wireGestures()
        binding.root.addOnLayoutChangeListener(positionControls)
        binding.fsBottomBar.addOnLayoutChangeListener(positionControls)
    }

    // --- Public surface (driven by MainActivity) ---------------------------

    /** Outer visibility. Every fresh show resets to bars-visible. */
    fun setVisible(visible: Boolean) {
        binding.root.isVisible = visible
        if (visible) {
            setBarsHidden(false)
            userSeeking = false
            binding.fsSeekFlashLeft.isVisible = false
            binding.fsSeekFlashRight.isVisible = false
            binding.fsGestureIndicator.isVisible = false
        } else {
            userSeeking = false
            durationSec = 0.0
            videoWidth = 0.0
            videoHeight = 0.0
            binding.fsBottomBar.translationY = 0f
            window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        }
    }

    val isVisible: Boolean get() = binding.root.isVisible

    fun dispose() {
        binding.fsSeekFlashLeft.removeCallbacks(hideFlashLeft)
        binding.fsSeekFlashRight.removeCallbacks(hideFlashRight)
        binding.fsGestureIndicator.removeCallbacks(hideIndicator)
        binding.root.setOnTouchListener(null)
        binding.root.removeOnLayoutChangeListener(positionControls)
        binding.fsBottomBar.removeOnLayoutChangeListener(positionControls)
        binding.fsBottomBar.translationY = 0f
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
    }

    /** Render a "state"/"takeover" snapshot from the content script. */
    fun renderState(s: JSONObject) {
        // Media duration may be Infinity while live, and bridge JSON can carry
        // null for a non-finite position. Never pass either into roundToInt().
        val pos = finiteMediaTime(s.optDouble("pos", 0.0))
        durationSec = finiteMediaTime(s.optDouble("dur", 0.0))
        videoWidth = finiteMediaTime(s.optDouble("videoWidth", 0.0))
        videoHeight = finiteMediaTime(s.optDouble("videoHeight", 0.0))
        scaleMode = s.optString("scale", "contain")
        binding.fsSeek.isEnabled = durationSec > 0.0
        if (durationSec <= 0.0) userSeeking = false
        positionBottomBar()
        val playing = !(s.optBoolean("paused", true) || s.optBoolean("ended", false))

        binding.fsPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_filled
        )
        binding.fsPlayPause.contentDescription = binding.root.context.getString(
            if (playing) R.string.player_pause else R.string.player_play
        )
        binding.fsRepeat.setColorFilter(
            if (s.optBoolean("loop")) ACCENT else Color.WHITE
        )

        if (!userSeeking) {
            binding.fsSeek.progress =
                if (durationSec > 0) ((pos / durationSec).coerceIn(0.0, 1.0) * SEEK_MAX).roundToInt() else 0
            binding.fsTime.text = formatTimePair(pos)
        }
    }

    // --- Buttons ------------------------------------------------------------

    private fun wireButtons() = with(binding) {
        fsBack.setOnClickListener { onExit() }
        fsExit.setOnClickListener { onExit() }
        fsPip.setOnClickListener { onPip() }
        fsRotate.setOnClickListener { onRotate() }
        fsLock.setOnClickListener { setBarsHidden(true) }

        fsPlayPause.setOnClickListener { bridge.sendCommand("toggle") }
        fsRepeat.setOnClickListener { bridge.sendCommand("loop") }
        fsPrev.setOnClickListener { seekBy(-seekSeconds()) }
        fsNext.setOnClickListener { seekBy(+seekSeconds()) }

        // Pop the system volume slider; precise control is the drag gesture.
        fsVolume.setOnClickListener {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_SAME,
                AudioManager.FLAG_SHOW_UI,
            )
        }

        // Stubs — Banana-parity icons, no functional handler yet.
        fsHd.setOnClickListener { }
        fsPlaylist.setOnClickListener { }
    }

    private fun wireSeekBar() {
        binding.fsSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = durationSec > 0.0
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && durationSec > 0) {
                    // Live preview of the target time while scrubbing.
                    binding.fsTime.text =
                        formatTimePair(durationSec * progress / SEEK_MAX)
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!userSeeking || durationSec <= 0.0) {
                    userSeeking = false
                    return
                }
                userSeeking = false
                val frac = ((seekBar?.progress ?: 0).toDouble() / SEEK_MAX).coerceIn(0.0, 1.0)
                bridge.sendCommand("seekTo") { put("frac", frac) }
            }
        })
    }

    // --- Gestures -----------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun wireGestures() {
        val detector = GestureDetector(
            binding.root.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    setBarsHidden(!barsHidden)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val w = binding.root.width
                    seekBy(if (e.x < w / 2f) -seekSeconds() else seekSeconds())
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    val start = e1 ?: return false
                    val totalDx = e2.x - start.x
                    val totalDy = start.y - e2.y // positive = swipe up

                    if (drag == Drag.NONE) {
                        // Commit to a vertical drag only once it's clearly
                        // vertical — otherwise leave taps/double-taps alone.
                        if (abs(totalDy) < touchSlop * 2 || abs(totalDy) < abs(totalDx)) {
                            return false
                        }
                        drag = if (start.x > binding.root.width / 2f) Drag.VOLUME else Drag.BRIGHTNESS
                        dragStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        dragStartBrightness = window.attributes.screenBrightness
                            .takeIf { it >= 0f } ?: 0.5f
                    }

                    // Dragging ~70% of the overlay height sweeps the full range.
                    val range = binding.root.height * 0.7f
                    if (range <= 0f) return false
                    val frac = totalDy / range
                    when (drag) {
                        Drag.VOLUME -> {
                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            if (max <= 0) return true
                            val vol = (dragStartVolume + frac * max).roundToInt().coerceIn(0, max)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                            showIndicator(
                                binding.root.context.getString(
                                    R.string.player_volume, vol * 100 / max
                                )
                            )
                        }
                        Drag.BRIGHTNESS -> {
                            val b = (dragStartBrightness + frac).coerceIn(0.01f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = b }
                            showIndicator(
                                binding.root.context.getString(
                                    R.string.player_brightness, (b * 100).roundToInt()
                                )
                            )
                        }
                        Drag.NONE -> Unit
                    }
                    return true
                }
            },
        )

        binding.root.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (drag != Drag.NONE) {
                    drag = Drag.NONE
                    binding.fsGestureIndicator.postDelayed(hideIndicator, INDICATOR_LINGER_MS)
                }
            }
            true
        }
    }

    private fun seekBy(deltaSeconds: Int) {
        if (durationSec <= 0.0) return
        bridge.sendCommand("seekBy") { put("delta", deltaSeconds) }
        val ctx = binding.root.context
        if (deltaSeconds < 0) {
            flash(binding.fsSeekFlashLeft, ctx.getString(R.string.player_seek_back, -deltaSeconds), hideFlashLeft)
        } else {
            flash(binding.fsSeekFlashRight, ctx.getString(R.string.player_seek_forward, deltaSeconds), hideFlashRight)
        }
    }

    private fun flash(view: TextView, text: String, hide: Runnable) {
        view.removeCallbacks(hide)
        view.text = text
        view.isVisible = true
        view.postDelayed(hide, FLASH_MS)
    }

    private fun showIndicator(text: String) {
        binding.fsGestureIndicator.removeCallbacks(hideIndicator)
        binding.fsGestureIndicator.text = text
        binding.fsGestureIndicator.isVisible = true
    }

    /**
     * Hide / show just the top + bottom bars while keeping the gesture layer
     * alive (so taps toggle them back). Triggered by 🔒 or a single tap.
     */
    private fun setBarsHidden(hidden: Boolean) {
        barsHidden = hidden
        binding.fsTopBar.isVisible = !hidden
        binding.fsBottomBar.isVisible = !hidden
        if (!hidden) positionBottomBar()
    }

    /** Keep the seek row next to a letterboxed portrait frame, including after rotation. */
    private fun positionBottomBar() {
        val root = binding.root
        val bar = binding.fsBottomBar
        if (!root.isVisible || root.height <= 0 || bar.height <= 0) return
        val top = playerControlsTop(root.width, root.height, videoWidth, videoHeight,
            bar.height, scaleMode == "contain")
        bar.translationY = (top - bar.top).toFloat()
    }

    private fun formatTimePair(posSec: Double): String =
        "${formatTime(posSec)} | ${formatTime(durationSec)}"

    private fun formatTime(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private companion object {
        const val SEEK_MAX = 1000
        const val FLASH_MS = 650L
        const val INDICATOR_LINGER_MS = 500L
        val ACCENT = Color.parseColor("#FFC536")
    }
}

internal fun finiteMediaTime(value: Double): Double = value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

/** Pixel position of the controls below a centered contain frame; other modes stay at the bottom. */
internal fun playerControlsTop(
    viewportWidth: Int,
    viewportHeight: Int,
    videoWidth: Double,
    videoHeight: Double,
    controlsHeight: Int,
    fit: Boolean,
): Int {
    val bottom = (viewportHeight - controlsHeight).coerceAtLeast(0)
    if (!fit || viewportWidth <= 0 || viewportHeight <= viewportWidth ||
        !videoWidth.isFinite() || !videoHeight.isFinite() || videoWidth <= 0.0 || videoHeight <= 0.0) return bottom
    val displayedHeight = minOf(viewportHeight.toDouble(), viewportWidth.toDouble() / videoWidth * videoHeight)
    return ((viewportHeight + displayedHeight) / 2.0).roundToInt().coerceIn(0, bottom)
}
