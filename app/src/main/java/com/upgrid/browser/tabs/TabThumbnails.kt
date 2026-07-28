package com.upgrid.browser.tabs

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Page previews for the tabs grid, keyed by tab id.
 *
 * Deliberately memory-only and deliberately small. A thumbnail is a decoded
 * bitmap sitting next to GeckoView's own per-tab memory, and the tray is a
 * screen the user glances at for a second — paying disk I/O and a cache
 * directory for it would cost more than it returns. Missing previews fall back
 * to the letter tile, which is what a restored session shows anyway.
 *
 * Captured through `EngineView.captureThumbnail`, which can only ever see the
 * tab currently rendered. So: capture the selected tab just before leaving for
 * the grid, and again whenever the selection changes. Everything else keeps
 * whatever it had when it was last on screen — same as Chrome.
 */
class TabThumbnails {

    /**
     * Sized in kilobytes rather than entries: a phone-resolution capture is a
     * few hundred KB and a tablet's is several MB, so an entry count would mean
     * wildly different memory on different devices.
     */
    private val cache = object : LruCache<String, Bitmap>(MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    operator fun get(tabId: String): Bitmap? = cache.get(tabId)

    /**
     * Store a capture, scaled down to [TARGET_WIDTH].
     *
     * The engine hands back a full-window bitmap — on a 1080p phone that's ~8 MB
     * for something drawn into a 170dp-wide card. Scaling on the way in is what
     * keeps a dozen tabs from being worth more than the cache cap.
     */
    fun put(tabId: String, bitmap: Bitmap) {
        val scaled = runCatching { scale(bitmap) }.getOrNull() ?: return
        cache.put(tabId, scaled)
        // The source came straight from the engine and nothing else holds it.
        if (scaled !== bitmap) bitmap.recycle()
    }

    fun remove(tabId: String) {
        cache.remove(tabId)
    }

    /** Drop previews for tabs that no longer exist. Cheap; call on tab changes. */
    fun retainOnly(tabIds: Set<String>) {
        cache.snapshot().keys.forEach { if (it !in tabIds) cache.remove(it) }
    }

    private fun scale(source: Bitmap): Bitmap {
        if (source.width <= TARGET_WIDTH) return source
        val height = (source.height.toFloat() * TARGET_WIDTH / source.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, TARGET_WIDTH, height, true)
    }

    private companion object {
        const val TARGET_WIDTH = 360
        const val MAX_KB = 6 * 1024
    }
}
