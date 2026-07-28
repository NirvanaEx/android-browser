package com.upgrid.browser.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Live completions from the user's chosen search engine.
 *
 * This is what makes the omnibar drop-down useful on a fresh install: with no
 * bookmarks and no history there is nothing local to offer, and a drop-down
 * that only appears once you've already been somewhere is a drop-down nobody
 * trusts.
 *
 * Deliberately small: no queue, no retries. A suggestion that arrives late is
 * worthless — the user has typed another letter by then — so any failure is
 * just an empty list. The caller has already rendered its local results and
 * keeps them.
 *
 * Worth being explicit about: this sends what's being typed to the search
 * engine. That's the deal every browser's omnibar makes, and it's the engine
 * the user picked in Settings — but it is traffic that wouldn't otherwise leave
 * the device, so it fires only while the URL bar is actually being edited.
 */
class SearchSuggestionClient(
    private val client: Client,
    private val engine: () -> SearchEngine,
) {

    /**
     * The last few answers, keyed by engine + query.
     *
     * Typing forwards and then backspacing walks back over queries that were
     * just asked, and every browser's omnibar does that constantly. A handful
     * of entries turns the common case into no request at all, which matters
     * more here than anywhere else: this is the one network call made from a
     * keystroke.
     */
    private val recent = object : LinkedHashMap<String, List<String>>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) =
            size > CACHE_ENTRIES
    }

    /**
     * Completions for [query], best first. Empty on any failure — offline, a
     * dead endpoint, a payload we don't recognise.
     */
    suspend fun suggestionsFor(query: String): List<String> = withContext(Dispatchers.IO) {
        val chosen = engine()
        val key = "${chosen.key}\u0000$query"
        synchronized(recent) { recent[key] }?.let { return@withContext it }

        val request = Request(
            url = chosen.suggestUrlFor(query),
            method = Request.Method.GET,
            // Left to itself the fetch layer signs these "MozacFetch/<version>",
            // which is not a string any suggest endpoint has ever been tested
            // against. Asking as a browser is both more honest about what this
            // is and less likely to be answered oddly.
            headers = MutableHeaders(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/javascript, */*",
            ),
            connectTimeout = TIMEOUT,
            readTimeout = TIMEOUT,
        )

        try {
            val completions = client.fetch(request).use { response ->
                if (response.status !in SUCCESS) {
                    Log.d(TAG, "Suggest endpoint answered ${response.status}")
                    return@withContext emptyList()
                }
                parse(response.body.string())
            }
            synchronized(recent) { recent[key] = completions }
            completions
        } catch (t: Throwable) {
            // Nothing here is worth interrupting the user over: they typed a
            // letter and the network didn't answer.
            Log.d(TAG, "Suggest request failed", t)
            emptyList()
        }
    }

    /**
     * OpenSearch suggestions: `["query", ["completion", …], …]`. Anything after
     * element 1 (descriptions, URLs, Google's metadata object) is ignored —
     * only element 1 carries completions, and every engine we ship agrees on
     * that much.
     */
    private fun parse(body: String): List<String> = try {
        val completions = JSONArray(body).optJSONArray(1) ?: JSONArray()
        (0 until completions.length())
            .mapNotNull { completions.optString(it, "").takeIf(String::isNotBlank) }
            .take(MAX_RESULTS)
    } catch (t: Throwable) {
        Log.d(TAG, "Unrecognised suggest payload", t)
        emptyList()
    }

    private companion object {
        const val TAG = "SearchSuggest"

        val SUCCESS = 200..299

        /**
         * Five seconds, not three.
         *
         * The old three covered a warm connection and nothing else: the first
         * completion of a session pays DNS plus a TLS handshake on a mobile
         * link, and on anything worse than good 4G that lands past three
         * seconds — so the very first drop-down, the one that decides whether
         * the user believes this feature exists, was the one most likely to
         * come back empty. Nothing waits on this; a late answer is dropped by
         * the next keystroke anyway.
         */
        val TIMEOUT = 5L to TimeUnit.SECONDS

        const val USER_AGENT =
            "Mozilla/5.0 (Android 13; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0"

        /** The drop-down trims further; this just bounds the parse. */
        const val MAX_RESULTS = 10

        const val CACHE_ENTRIES = 24
    }
}
