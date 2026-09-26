package com.upgrid.browser.fullscreen

import android.app.Activity
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.upgrid.browser.R
import org.json.JSONObject

/** An independent native window: its surface and controls never depend on website CSS. */
@UnstableApi
class NativeVideoPlayer(
    private val activity: Activity,
    private val stream: JSONObject,
    private val onClosed: (position: Double, paused: Boolean, resume: Boolean) -> Unit,
    private val onError: (code: String) -> Unit,
) {
    private val dialog = Dialog(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val orientation = activity.requestedOrientation
    private var scaleDialog: android.app.AlertDialog? = null
    private var mirrorDialog: android.app.AlertDialog? = null
    private var mirrorHorizontal = false
    private var mirrorVertical = false
    private var videoWidth = 0.0
    private var videoHeight = 0.0
    private val controlMargins = mutableMapOf<View, Int>()
    private val startPosition = stream.optDouble("pos", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
    private val originallyPaused = stream.optBoolean("paused")
    private var resumePage = true
    private var released = false
    private var firstFrame = false
    private var sourceIndex = 0
    private var prepareStartedAt = 0L
    private val sources = stream.optJSONArray("sources")?.let { list ->
        (0 until minOf(list.length(), 4)).mapNotNull { list.optJSONObject(it) }
    }?.takeIf { it.isNotEmpty() } ?: listOf(JSONObject().put("url", stream.optString("url")))
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var seekFeedback: TextView? = null
    private val hideSeekFeedback = Runnable { seekFeedback?.visibility = View.GONE }
    private val timeout = Runnable { retryOrFail("prepare_timeout") }

    fun show() {
        try {
            val source = android.net.Uri.parse(stream.getString("url"))
            require(source.scheme in setOf("https", "http") && !source.host.isNullOrBlank() && source.userInfo == null)
            val http = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(3_000).setReadTimeoutMs(4_000)
                .setUserAgent(header(stream.optString("userAgent")))
                .setDefaultRequestProperties(mapOf("Referer" to header(stream.optString("referrer"))))
            val exo = ExoPlayer.Builder(activity)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .setRenderersFactory(DefaultRenderersFactory(activity).setEnableDecoderFallback(true))
                .setLoadControl(DefaultLoadControl.Builder().setTargetBufferBytes(24 * 1024 * 1024)
                    .setBufferDurationsMs(10_000, 30_000, 250, 1_000).build())
                .setSeekBackIncrementMs(5_000).setSeekForwardIncrementMs(5_000).build()
            player = exo
            exo.setAudioAttributes(AudioAttributes.DEFAULT, true)
            exo.setHandleAudioBecomingNoisy(true)
            exo.repeatMode = if (stream.optBoolean("loop")) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            exo.volume = if (stream.optBoolean("muted")) 0f else 1f
            exo.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    firstFrame = true
                    handler.removeCallbacks(timeout)
                    android.util.Log.i("UpgridNativePlayer", "first_frame")
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                override fun onPlayerError(error: PlaybackException) = retryOrFail("media3_${error.errorCode}")
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoWidth = finiteMediaTime(videoSize.width.toDouble() * videoSize.pixelWidthHeightRatio)
                    videoHeight = finiteMediaTime(videoSize.height.toDouble())
                    positionNativeControls()
                }
            })
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCanceledOnTouchOutside(false)
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
            val bar = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            fun button(icon: Int, label: Int, action: () -> Unit) = ImageButton(activity).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = activity.getString(label)
                setOnClickListener { action() }
                bar.addView(this, LinearLayout.LayoutParams(dp(56), dp(56)))
            }
            button(android.R.drawable.ic_menu_close_clear_cancel, R.string.player_native_back) { close() }
            bar.addView(TextView(activity).apply {
                text = activity.getString(R.string.player_native_title)
                setTextColor(Color.WHITE)
                textSize = 18f
                maxLines = 1
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val muteButton = button(
                if (exo.volume == 0f) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off,
                if (exo.volume == 0f) R.string.player_native_unmute else R.string.player_native_mute,
            ) {}
            muteButton.setOnClickListener {
                exo.volume = if (exo.volume == 0f) 1f else 0f
                muteButton.setImageResource(if (exo.volume == 0f) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off)
                muteButton.contentDescription = activity.getString(if (exo.volume == 0f) R.string.player_native_unmute else R.string.player_native_mute)
            }
            button(R.drawable.ic_player_scale, R.string.player_scale) {
                val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
                scaleDialog?.dismiss()
                scaleDialog = android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.player_scale)
                    .setSingleChoiceItems(arrayOf(activity.getString(R.string.player_scale_fit),
                        activity.getString(R.string.player_scale_cover), activity.getString(R.string.player_scale_stretch)),
                        modes.indexOf(playerView?.resizeMode ?: modes[0])) { picker, index ->
                        playerView?.resizeMode = modes[index]
                        positionNativeControls()
                        picker.dismiss()
                    }.show()
            }
            val mirrorButton = button(R.drawable.ic_player_mirror, R.string.player_mirror) {}
            mirrorButton.setOnClickListener {
                mirrorDialog?.dismiss()
                mirrorDialog = android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.player_mirror)
                    .setMultiChoiceItems(arrayOf(activity.getString(R.string.player_mirror_horizontal),
                        activity.getString(R.string.player_mirror_vertical)),
                        booleanArrayOf(mirrorHorizontal, mirrorVertical)) { _, index, checked ->
                        if (index == 0) mirrorHorizontal = checked else mirrorVertical = checked
                        // Transform just the TextureView, keeping controls and gestures unchanged.
                        playerView?.videoSurfaceView?.apply {
                            scaleX = if (mirrorHorizontal) -1f else 1f
                            scaleY = if (mirrorVertical) -1f else 1f
                        }
                        mirrorButton.setColorFilter(if (mirrorHorizontal || mirrorVertical)
                            Color.rgb(255, 197, 54) else Color.WHITE)
                    }.setPositiveButton(android.R.string.ok, null).show()
            }
            button(android.R.drawable.ic_menu_rotate, R.string.player_native_rotate) {
                activity.requestedOrientation = if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            root.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
            val view = (activity.layoutInflater.inflate(R.layout.view_native_player, root, false) as PlayerView).apply {
                player = exo
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                setShowSubtitleButton(true)
                setRepeatToggleModes(1)
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowRewindButton(false)
                setShowFastForwardButton(false)
                setShutterBackgroundColor(Color.BLACK)
            }
            playerView = view
            listOf(androidx.media3.ui.R.id.exo_bottom_bar, androidx.media3.ui.R.id.exo_progress,
                androidx.media3.ui.R.id.exo_minimal_controls).forEach { id ->
                view.findViewById<View>(id)?.let { control ->
                    (control.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                        controlMargins[control] = params.bottomMargin
                    }
                }
            }
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionNativeControls() }
            installTapSeeking(view, exo)
            root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            dialog.setContentView(root)
            dialog.setOnDismissListener { dispose() }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.black)
                setDimAmount(0f)
                setLayout(-1, -1)
            }
            prepareStartedAt = SystemClock.elapsedRealtime()
            prepareSource(startPosition, originallyPaused)
        } catch (_: Exception) {
            fail("native_initialization")
        }
    }

    private fun positionNativeControls() {
        val view = playerView ?: return
        val progress = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress) ?: return
        val margin = controlMargins[progress] ?: return
        if (view.height <= 0 || progress.height <= 0) return
        val baseTop = view.height - margin - progress.height
        val top = playerControlsTop(view.width, view.height, videoWidth, videoHeight,
            view.height - baseTop, view.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT)
        val offset = (baseTop - top).coerceAtLeast(0)
        controlMargins.forEach { (control, originalMargin) ->
            val params = control.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
            if (params.bottomMargin != originalMargin + offset) {
                params.bottomMargin = originalMargin + offset
                control.layoutParams = params
            }
        }
    }

    private fun prepareSource(position: Double, paused: Boolean) {
        val item = sources[sourceIndex]
        val uri = android.net.Uri.parse(item.getString("url"))
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        val mime = when (item.optString("mimeType").lowercase()) {
            "hls", "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
            "dash", "application/dash+xml" -> "application/dash+xml"
            "video/mp4", "video/webm" -> item.optString("mimeType").lowercase()
            else -> null
        }
        player?.apply {
            setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(mime).build(), (position * 1000).toLong())
            playWhenReady = !paused
            prepare()
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 3_000)
    }

    private fun retryOrFail(code: String) {
        if (released) return
        if (sourceIndex + 1 < minOf(sources.size, 2) &&
            SystemClock.elapsedRealtime() - prepareStartedAt < 6_000) {
            val position = if (firstFrame) (player?.currentPosition ?: 0).coerceAtLeast(0) / 1000.0 else startPosition
            val paused = player?.playWhenReady != true
            sourceIndex++
            try { prepareSource(position, paused); return } catch (_: Exception) { }
        }
        fail(code)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installTapSeeking(view: PlayerView, exo: ExoPlayer) {
        // Child controls receive their touches first. Only the remaining video
        // area reaches this listener; time-bar drags and buttons stay native.
        view.controllerHideOnTouch = true
        val feedback = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
            setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        seekFeedback = feedback
        view.addView(feedback)
        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                view.performClick()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (released || !firstFrame) return true
                val backward = event.x < view.width / 2f
                val command = if (backward) Player.COMMAND_SEEK_BACK else Player.COMMAND_SEEK_FORWARD
                if (!exo.isCommandAvailable(command)) return true
                if (backward) exo.seekBack() else exo.seekForward()
                feedback.text = activity.getString(if (backward) R.string.player_native_seek_back else R.string.player_native_seek_forward)
                feedback.layoutParams = FrameLayout.LayoutParams(view.width / 2, -2,
                    Gravity.CENTER_VERTICAL or if (backward) Gravity.LEFT else Gravity.RIGHT)
                feedback.visibility = View.VISIBLE
                handler.removeCallbacks(hideSeekFeedback)
                handler.postDelayed(hideSeekFeedback, 650)
                return true
            }
        })
        view.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            true
        }
    }

    /** Returning to the app must never restart playback automatically. */
    fun pause() {
        player?.pause()
    }

    /** Backgrounding never resumes audio in the now invisible browser page. */
    fun close(resume: Boolean = true) {
        if (released) return
        resumePage = resume
        // Dialog's OnDismiss is posted asynchronously. Return the final position
        // before the host sends its subsequent stop/destroy cancellation.
        dispose()
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun fail(code: String) {
        if (released) return
        onError(code) // Only technical codes; no URL or exception message.
        close(false)
    }

    private fun dispose() {
        if (released) return
        released = true
        scaleDialog?.dismiss()
        scaleDialog = null
        mirrorDialog?.dismiss()
        mirrorDialog = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(hideSeekFeedback)
        val exo = player
        val position = if (firstFrame && exo != null) exo.currentPosition.coerceAtLeast(0) / 1000.0 else startPosition
        val paused = if (exo == null) originallyPaused else !exo.playWhenReady || exo.playbackState == Player.STATE_ENDED
        playerView?.player = null
        exo?.release()
        player = null
        playerView = null
        controlMargins.clear()
        seekFeedback = null
        activity.requestedOrientation = orientation
        onClosed(position, paused, resumePage)
    }

    private fun header(value: String) = value.take(512).replace("\r", "").replace("\n", "")
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
