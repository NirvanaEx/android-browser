package com.upgrid.browser.prefs

import android.content.Context
import android.content.SharedPreferences
import com.upgrid.browser.search.SearchEngine

/**
 * Single typed entry point for app-wide settings backed by SharedPreferences.
 *
 * Anything user-configurable lands here so the rest of the codebase doesn't
 * touch raw preference keys. New settings: add a key, a typed getter/setter,
 * surface in [com.upgrid.browser.settings.SettingsBottomSheet].
 */
class BrowserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Default search engine for omnibar queries. Yandex out of the box. */
    var searchEngine: SearchEngine
        get() = SearchEngine.fromKeyOrDefault(prefs.getString(KEY_SEARCH_ENGINE, null))
        set(value) {
            prefs.edit().putString(KEY_SEARCH_ENGINE, value.key).apply()
        }

    /**
     * Seconds the built-in player skips on a double-tap (left = back,
     * right = forward) and on the ⏮/⏭ buttons. One of [PLAYER_SEEK_OPTIONS].
     */
    var playerSeekSeconds: Int
        get() = prefs.getInt(KEY_PLAYER_SEEK, PLAYER_SEEK_DEFAULT)
        set(value) {
            prefs.edit().putInt(KEY_PLAYER_SEEK, value).apply()
        }

    companion object {
        private const val FILE = "upgrid_prefs"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_PLAYER_SEEK = "player_seek_seconds"

        const val PLAYER_SEEK_DEFAULT = 10
        val PLAYER_SEEK_OPTIONS = listOf(5, 10, 15, 30)
    }
}
