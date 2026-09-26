package org.mozilla.fenix.upgrid

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpgridVideoPosterTest {
    @Test fun `selected image appears while loading and is removed on first frame or disposal`() {
        val png = java.io.ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
        }.toByteArray()
        server(png, 200) { url ->
            val parent = FrameLayout(testContext)
            val poster = VideoPoster(parent, url, "")
            try {
                await { (parent.getChildAt(0) as? ImageView)?.drawable != null }
                assertEquals(ImageView.ScaleType.FIT_CENTER, (parent.getChildAt(0) as ImageView).scaleType)
                poster.close()
                assertEquals(0, parent.childCount)
                poster.close()
            } finally { poster.close() }
        }
    }

    @Test fun `failed or oversized poster never leaves a stale image overlay`() {
        for ((body, code) in listOf(byteArrayOf() to 404, ByteArray(2 * 1024 * 1024 + 1) to 200)) {
            server(body, code) { url ->
                val parent = FrameLayout(testContext)
                val poster = VideoPoster(parent, url, "")
                try { await { parent.childCount == 0 } } finally { poster.close() }
            }
        }
    }

    @Test fun `closing during download cannot reattach its image`() {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/poster") { response ->
            requested.countDown()
            release.await(3, TimeUnit.SECONDS)
            response.close()
        }
        server.start()
        val parent = FrameLayout(testContext)
        val poster = VideoPoster(parent, "http://127.0.0.1:${server.address.port}/poster", "")
        try {
            assertTrue(requested.await(3, TimeUnit.SECONDS))
            poster.close()
            release.countDown()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, parent.childCount)
        } finally { release.countDown(); poster.close(); server.stop(0) }
    }

    private fun server(body: ByteArray, status: Int, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/poster") { response ->
            try {
                response.sendResponseHeaders(status, body.size.toLong())
                response.responseBody.use { it.write(body) }
            } catch (_: java.io.IOException) {
                // Expected when a bounded reader closes an oversized response.
            } finally { response.close() }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/poster") } finally { server.stop(0) }
    }

    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Poster did not settle within five seconds", ready())
    }
}
