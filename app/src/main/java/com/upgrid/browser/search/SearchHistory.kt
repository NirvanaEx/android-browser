package com.upgrid.browser.search

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple capped FIFO of recent omnibar search queries.
 *
 * Storage: SharedPreferences as a single delimited string. Plain text is fine —
 * this is the user's own search history on their own device, no need for Room
 * or a DB at this volume (~50 entries). De-duplicates on insert: if the same
 * query is recorded again, it moves to the front instead of stacking.
 *
 * The delimiter is a unit-separator (U+001F) so it can never collide with a
 * legitimate query character.
 */
class SearchHistory(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Most-recent first, capped to [MAX_ENTRIES]. */
    fun recent(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEP)
    }

    /**
     * Record a query at the front of the list. Empty / whitespace-only inputs
     * are ignored. Existing entries with the same text are removed first so the
     * user sees the freshest occurrence rather than a stale duplicate.
     */
    fun record(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = recent().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
        prefs.edit()
            .putString(KEY, current.joinToString(SEP.toString()))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val FILE = "upgrid_history"
        private const val KEY = "queries"
        private const val SEP = ''   // ASCII unit separator — invalid in real text
        const val MAX_ENTRIES = 50
    }
}
