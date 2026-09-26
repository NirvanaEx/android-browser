package com.upgrid.browser.fullscreen

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Only the selected video's poster, bounded and cancellable; never required for playback. */
class VideoPoster(private val parent: FrameLayout, url: String, referrer: String) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    private var task: Future<*>? = null
    private val image = ImageView(parent.context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(Color.BLACK)
    }

    init {
        if (url.isNotBlank() && url.length <= 8192) {
            parent.addView(image, FrameLayout.LayoutParams(-1, -1))
            task = executor.submit {
                try {
                    var uri = URI(url)
                    var bytes: ByteArray? = null
                    for (redirect in 0..3) {
                        if (closed || Thread.currentThread().isInterrupted) return@submit
                        require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null)
                        val current = uri.toURL().openConnection() as HttpURLConnection
                        connection = current
                        current.connectTimeout = 3_000
                        current.readTimeout = 3_000
                        current.instanceFollowRedirects = false
                        if (referrer.isNotBlank() && !(referrer.startsWith("https:") && uri.scheme == "http"))
                            current.setRequestProperty("Referer", referrer.take(512).replace("\r", "").replace("\n", ""))
                        try {
                            if (current.responseCode in listOf(301, 302, 303, 307, 308)) {
                                val next = uri.resolve(current.getHeaderField("Location") ?: error("redirect"))
                                require(!(uri.scheme == "https" && next.scheme == "http"))
                                uri = next
                                continue
                            }
                            require(current.responseCode == 200)
                            bytes = current.inputStream.use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= 2 * 1024 * 1024)
                                    if (closed || Thread.currentThread().isInterrupted) return@submit
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            break
                        } finally { current.disconnect(); connection = null }
                    }
                    val data = bytes ?: return@submit
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    require(options.outWidth in 1..8192 && options.outHeight in 1..8192)
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return@submit
                    handler.post {
                        if (!closed) image.setImageBitmap(bitmap) else bitmap.recycle()
                    }
                } catch (_: Exception) { handler.post { if (!closed) close() } }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        connection?.disconnect()
        task?.cancel(true)
        image.setImageDrawable(null)
        parent.removeView(image)
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "UpgridPoster").apply { isDaemon = true }
        }
    }
}
