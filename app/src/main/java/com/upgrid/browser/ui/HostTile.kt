package com.upgrid.browser.ui

import android.net.Uri
import androidx.annotation.ColorInt

/**
 * The letter-tile identity used everywhere a site is listed without a favicon:
 * history rows, bookmark rows, tab cards, speed-dial tiles.
 *
 * Favicons would be more accurate, but they arrive over the network and pop in
 * after the row is already on screen — in a fast-scrolling list that reads as
 * flicker. A letter on a per-host color is instant, stable, and gives the eye
 * something to pattern-match on when scanning a long list, which is the actual
 * job of the leading tile here.
 *
 * The color must be a pure function of the host: the same site has to look the
 * same in the tabs tray, in history and on the start page, and it has to
 * survive the row being rebound to a different position.
 */
object HostTile {

    /**
     * Saturated enough to read as an identity color, dark enough that white
     * text on top clears contrast in light theme. Deliberately not derived from
     * the M3 palette — these are meant to differ from each other, not to blend
     * into the app's surfaces.
     */
    private val PALETTE = intArrayOf(
        0xFF1F6FEB.toInt(), // blue (brand)
        0xFFD93F3F.toInt(), // red
        0xFF2E9E5B.toInt(), // green
        0xFFE07B29.toInt(), // orange
        0xFF7C4DBF.toInt(), // violet
        0xFF0F8B8D.toInt(), // teal
        0xFFC2185B.toInt(), // pink
        0xFF5B6B7C.toInt(), // slate
        0xFF9B7A1A.toInt(), // ochre
        0xFF3F51B5.toInt(), // indigo
    )

    /**
     * Stable color for a host.
     *
     * The hash is computed by hand rather than via [String.hashCode] because
     * that contract is only guaranteed within a single JVM run in principle,
     * and a color that silently reshuffles between app versions would make the
     * lists look randomly repainted after an update.
     */
    @ColorInt
    fun colorFor(host: String): Int {
        if (host.isEmpty()) return PALETTE[0]
        var hash = 0
        for (ch in host) {
            hash = (hash * 31 + ch.code) and 0x7FFFFFFF
        }
        return PALETTE[hash % PALETTE.size]
    }

    /** Leading glyph for the tile: first letter of the host, "?" if unknown. */
    fun letterFor(host: String): String =
        host.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    /** `https://www.github.com/x` → `github.com`. Empty for non-hierarchical URLs. */
    fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
}
