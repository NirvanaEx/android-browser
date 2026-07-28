package com.upgrid.browser.home

import androidx.annotation.ColorInt
import com.upgrid.browser.bookmarks.Bookmark
import com.upgrid.browser.ui.HostTile

/**
 * One tile on the speed-dial start page.
 *
 * [letter] and [color] are the fallback identity, drawn immediately; the real
 * favicon is fetched afterwards and painted on top, so a tile never starts
 * blank. See [StartPagePresenter].
 *
 * [saved] separates a tile the user added (backed by a bookmark, removing it
 * deletes the bookmark) from a built-in default (removing it only records the
 * URL in `BrowserPreferences.hiddenQuickLinks` — there's nothing to delete).
 */
data class QuickLink(
    val label: String,
    val url: String,
    val letter: String,
    @ColorInt val color: Int,
    val saved: Boolean = false,
) {
    companion object {
        /**
         * Defaults for a fresh install — mix of EN + RU resources covering
         * search / video / dev / reference / social.
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
         * A saved page as a tile. Letter and colour come from [HostTile], so a
         * bookmark looks the same here as it does in history and in the tabs
         * grid — and identical again if its favicon never loads.
         */
        fun of(bookmark: Bookmark): QuickLink {
            val host = bookmark.host.ifBlank { HostTile.hostOf(bookmark.url) }
            return QuickLink(
                // Page titles run long ("NirvanaEx/android-browser: a GeckoView
                // browser…"); under a 64dp tile the host is the part that
                // actually identifies the site.
                label = host.ifBlank { bookmark.title.ifBlank { bookmark.url } },
                url = bookmark.url,
                letter = HostTile.letterFor(host),
                color = HostTile.colorFor(host),
                saved = true,
            )
        }
    }
}
