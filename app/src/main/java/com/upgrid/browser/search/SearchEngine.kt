package com.upgrid.browser.search

import android.net.Uri

/**
 * Search engines available in the omnibar / settings picker.
 *
 * The order here is the order rendered in Settings; [YANDEX] is first because
 * we ship Yandex-default. To add an engine: append a constant, hand-craft its
 * search URL template, that's it.
 */
enum class SearchEngine(
    val key: String,
    val displayName: String,
    private val templateBuilder: (String) -> String,
) {
    YANDEX("yandex", "Yandex", { q ->
        "https://yandex.ru/search/?text=" + Uri.encode(q)
    }),
    GOOGLE("google", "Google", { q ->
        "https://www.google.com/search?q=" + Uri.encode(q)
    }),
    DUCKDUCKGO("ddg", "DuckDuckGo", { q ->
        "https://duckduckgo.com/?q=" + Uri.encode(q)
    }),
    BING("bing", "Bing", { q ->
        "https://www.bing.com/search?q=" + Uri.encode(q)
    });

    /** Build the full search URL for a free-text query (already trimmed by caller). */
    fun searchUrlFor(query: String): String = templateBuilder(query)

    companion object {
        /** Default engine — also the fallback when stored key is missing/unknown. */
        val DEFAULT = YANDEX

        fun fromKeyOrDefault(key: String?): SearchEngine =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
