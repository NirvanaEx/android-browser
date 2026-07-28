package com.upgrid.browser.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
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
 * Deliberately small: no cache, no retries, no queue. A suggestion that arrives
 * late is worthless — the user has typed another letter by then — so the
 * timeouts are short and any failure is just an empty list. The caller has
 * already rendered its local results and keeps them.
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
     * Completions for [query], best first. Empty on any failure — offline, a
     * dead endpoint, a payload we don't recognise.
     */
    suspend fun suggestionsFor(query: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request(
            url = engine().suggestUrlFor(query),
            method = Request.Method.GET,
            connectTimeout = TIMEOUT,
            readTimeout = TIMEOUT,
        )

        try {
            client.fetch(request).use { response ->
                if (response.status !in SUCCESS) return@withContext emptyList()
                parse(response.body.string())
            }
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

        /** Long enough for a phone on 4G, short enough not to outlive the query. */
        val TIMEOUT = 3L to TimeUnit.SECONDS

        /** The drop-down trims further; this just bounds the parse. */
        const val MAX_RESULTS = 10
    }
}
