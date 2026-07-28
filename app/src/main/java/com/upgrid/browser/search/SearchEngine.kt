package com.upgrid.browser.search

import android.net.Uri
import androidx.annotation.ColorInt

/**
 * Search engines available in the omnibar / settings picker.
 *
 * The order here is the order rendered in Settings; [YANDEX] is first because
 * we ship Yandex-default. To add an engine: append a constant, hand-craft its
 * search URL and its suggest URL, that's it.
 *
 * Every [suggestBuilder] points at an OpenSearch-style endpoint — one that
 * answers with `["the query", ["completion", "completion", …]]`. That shape is
 * what [SearchSuggestionClient] parses and it's the same endpoint Firefox uses
 * for these engines, so adding an engine means finding its `suggestions` URL,
 * not writing a second parser.
 *
 * [brandColor] is what the address bar tints its magnifier with while you type,
 * so the engine that's about to answer is visible without reading anything. A
 * colour rather than a logo: four marks would be four more files to keep, and
 * the one thing they'd have to be is instantly distinguishable, which the
 * colour already is at 24dp.
 */
enum class SearchEngine(
    val key: String,
    val displayName: String,
    @param:ColorInt val brandColor: Int,
    private val templateBuilder: (String) -> String,
    private val suggestBuilder: (String) -> String,
) {
    YANDEX(
        "yandex",
        "Yandex",
        0xFFFC3F1D.toInt(),
        { q -> "https://yandex.ru/search/?text=" + Uri.encode(q) },
        { q -> "https://suggest.yandex.ru/suggest-ff.cgi?part=" + Uri.encode(q) },
    ),
    GOOGLE(
        "google",
        "Google",
        0xFF4285F4.toInt(),
        { q -> "https://www.google.com/search?q=" + Uri.encode(q) },
        { q -> "https://suggestqueries.google.com/complete/search?client=firefox&q=" + Uri.encode(q) },
    ),
    DUCKDUCKGO(
        "ddg",
        "DuckDuckGo",
        0xFFDE5833.toInt(),
        { q -> "https://duckduckgo.com/?q=" + Uri.encode(q) },
        { q -> "https://duckduckgo.com/ac/?type=list&q=" + Uri.encode(q) },
    ),
    BING(
        "bing",
        "Bing",
        0xFF008373.toInt(),
        { q -> "https://www.bing.com/search?q=" + Uri.encode(q) },
        { q -> "https://api.bing.com/osjson.aspx?query=" + Uri.encode(q) },
    );

    /** Build the full search URL for a free-text query (already trimmed by caller). */
    fun searchUrlFor(query: String): String = templateBuilder(query)

    /** Where to ask this engine what the user is probably typing. */
    fun suggestUrlFor(query: String): String = suggestBuilder(query)

    companion object {
        /** Default engine — also the fallback when stored key is missing/unknown. */
        val DEFAULT = YANDEX

        fun fromKeyOrDefault(key: String?): SearchEngine =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
