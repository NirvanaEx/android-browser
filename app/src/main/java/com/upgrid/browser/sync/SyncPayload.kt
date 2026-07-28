package com.upgrid.browser.sync

import com.upgrid.browser.bookmarks.Bookmark
import com.upgrid.browser.history.HistoryEntry
import com.upgrid.browser.ui.HostTile
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single JSON document that lives in the user's Drive app-data folder.
 *
 * Shape:
 * ```
 * { "v": 1, "at": 1753680000000,
 *   "bookmarks": [ { "u": url, "t": title, "c": createdAt } ],
 *   "history":   [ { "u": url, "t": title, "a": visitedAt, "n": visits } ] }
 * ```
 *
 * Keys are one character because the whole history goes over the wire on every
 * sync — full-length names cost roughly a third of the payload for nothing.
 *
 * The format is versioned from the first release. A reader that meets a
 * [VERSION] it doesn't know refuses the document rather than guessing, so an
 * older install can't quietly drop fields a newer one added.
 */
data class SyncPayload(
    val updatedAt: Long,
    val bookmarks: List<Bookmark>,
    val history: List<HistoryEntry>,
) {

    fun toJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("at", updatedAt)
        put("bookmarks", JSONArray().apply {
            bookmarks.forEach { b ->
                put(JSONObject().apply {
                    put("u", b.url)
                    put("t", b.title)
                    put("c", b.createdAt)
                })
            }
        })
        put("history", JSONArray().apply {
            history.forEach { h ->
                put(JSONObject().apply {
                    put("u", h.url)
                    put("t", h.title)
                    put("a", h.visitedAt)
                    put("n", h.visits)
                })
            }
        })
    }.toString()

    companion object {
        const val VERSION = 1

        /** Name of the document inside `appDataFolder`. */
        const val FILE_NAME = "upgrid-sync.json"

        /**
         * How much history travels. The table holds up to 5000 rows; sending
         * all of them makes a multi-megabyte upload out of what is, for the
         * purpose of "continue on my other phone", a long tail nobody reads.
         */
        const val HISTORY_LIMIT = 2000

        /**
         * Parse a document fetched from Drive. Returns null for anything we
         * can't trust — malformed JSON, a future [VERSION], a truncated
         * download — because the result is fed straight into a merge that
         * writes to the local database.
         *
         * Individual malformed *rows* are skipped rather than failing the whole
         * document: one bad entry shouldn't cost the user every bookmark.
         */
        fun parse(json: String): SyncPayload? = runCatching {
            val root = JSONObject(json)
            if (root.optInt("v", 0) != VERSION) return null

            val bookmarks = root.optJSONArray("bookmarks").mapObjects { o ->
                val url = o.optString("u")
                if (url.isEmpty()) null
                else Bookmark(
                    id = 0L, // assigned by the local table on insert
                    url = url,
                    title = o.optString("t"),
                    host = HostTile.hostOf(url),
                    createdAt = o.optLong("c", 0L),
                )
            }

            val history = root.optJSONArray("history").mapObjects { o ->
                val url = o.optString("u")
                if (url.isEmpty()) null
                else HistoryEntry(
                    id = 0L,
                    url = url,
                    title = o.optString("t"),
                    host = HostTile.hostOf(url),
                    visitedAt = o.optLong("a", 0L),
                    visits = o.optInt("n", 1),
                )
            }

            SyncPayload(root.optLong("at", 0L), bookmarks, history)
        }.getOrNull()

        private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T?): List<T> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    val mapped = optJSONObject(i)?.let(transform)
                    if (mapped != null) add(mapped)
                }
            }
        }
    }
}
