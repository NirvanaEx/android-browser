package com.upgrid.browser.home

import androidx.annotation.ColorInt
import com.upgrid.browser.bookmarks.Bookmark
import com.upgrid.browser.ui.HostTile

/**
 * One tile on the speed-dial start page.
 *
 * [color] is the brand fill drawn behind [letter]. Letters are intentionally
 * a single character (or two for ambiguous brands like VK) to keep the tiles
 * visually clean — favicons would be more accurate but require a previous
 * visit, so the letter-tile placeholder is what new users see on first launch.
 */
data class QuickLink(
    val label: String,
    val url: String,
    val letter: String,
    @ColorInt val color: Int,
) {
    companion object {
        /**
         * Default seed for first launch — mix of EN + RU resources covering
         * search / video / dev / reference / social. Order matches a 4×2 grid
         * read left-to-right, top-to-bottom.
         */
        val SEED: List<QuickLink> = listOf(
            QuickLink("Google", "https://www.google.com/", "G", 0xFF4285F4.toInt()),
            QuickLink("YouTube", "https://www.youtube.com/", "Y", 0xFFFF0000.toInt()),
            QuickLink("GitHub", "https://github.com/", "G", 0xFF24292E.toInt()),
            QuickLink("Wikipedia", "https://en.wikipedia.org/", "W", 0xFF333333.toInt()),
            QuickLink("Yandex", "https://ya.ru/", "Я", 0xFFFFCC00.toInt()),
            QuickLink("VK", "https://vk.com/", "VK", 0xFF0077FF.toInt()),
            QuickLink("Habr", "https://habr.com/", "H", 0xFF77A2B6.toInt()),
            QuickLink("Reddit", "https://www.reddit.com/", "R", 0xFFFF4500.toInt()),
        )

        /**
         * A saved page as a speed-dial tile. Letter and color come from
         * [HostTile], so a bookmark's tile is the same block of color the site
         * gets in history, in the tabs grid and everywhere else.
         */
        fun of(bookmark: Bookmark): QuickLink {
            val host = bookmark.host.ifBlank { HostTile.hostOf(bookmark.url) }
            return QuickLink(
                // Page titles are long ("NirvanaEx/android-browser: a GeckoView
                // browser…"); under a 60dp tile the host is the part that
                // actually identifies the site.
                label = host.ifBlank { bookmark.title.ifBlank { bookmark.url } },
                url = bookmark.url,
                letter = HostTile.letterFor(host),
                color = HostTile.colorFor(host),
            )
        }
    }
}
