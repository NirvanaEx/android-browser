package com.upgrid.browser.prefs

import android.content.Context
import android.content.SharedPreferences
import com.upgrid.browser.search.SearchEngine
import com.upgrid.browser.ui.ThemeMode

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

    /**
     * Light, dark, or whatever the phone is doing. Applied by
     * [com.upgrid.browser.ui.ThemeMode.applyStored] on app start and again by
     * the settings sheet when it changes.
     */
    var themeMode: ThemeMode
        get() = ThemeMode.fromKeyOrDefault(prefs.getString(KEY_THEME, null))
        set(value) {
            prefs.edit().putString(KEY_THEME, value.key).apply()
        }

    /** Default search engine for omnibar queries. Yandex out of the box. */
    var searchEngine: SearchEngine
        get() = SearchEngine.fromKeyOrDefault(prefs.getString(KEY_SEARCH_ENGINE, null))
        set(value) {
            prefs.edit().putString(KEY_SEARCH_ENGINE, value.key).apply()
        }

    /**
     * Keep playing after leaving the tab or locking the screen.
     *
     * Off by default. A video that carries on talking once you've navigated
     * away is a bug in every reading but one — listening to a YouTube tab with
     * the screen off — and that one is worth a switch rather than a guess.
     */
    var backgroundPlayback: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_PLAYBACK, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BACKGROUND_PLAYBACK, value).apply()
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

    /**
     * When the last successful Drive sync finished, or 0 if never. Written by
     * [com.upgrid.browser.sync.SyncEngine]; read by settings and by the
     * auto-sync check on resume.
     */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC, value).apply()
        }

    /**
     * Sync in the background when the app comes to the foreground and the last
     * run is older than [AUTO_SYNC_INTERVAL_MS]. Off means the "Sync now"
     * button is the only trigger.
     */
    var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()
        }

    /**
     * Where the browser's own account lives.
     *
     * A static JSON per account behind HTTP basic auth — see
     * [com.upgrid.browser.account.AccountApi]. Editable because the address of
     * somebody's own server is not something to bake in for good, and blank is
     * allowed: with no server, sign-in falls back to the device's own copy of
     * the account and whatever profile it already holds.
     */
    var accountServer: String
        get() = prefs.getString(KEY_ACCOUNT_SERVER, DEFAULT_ACCOUNT_SERVER).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ACCOUNT_SERVER, value.trim()).apply()
        }

    /**
     * Offer to save passwords, and fill saved ones.
     *
     * One switch for both halves on purpose: a browser that keeps filling
     * passwords after you told it to stop saving them is not honouring the
     * switch, whatever the wording says.
     */
    var savePasswords: Boolean
        get() = prefs.getBoolean(KEY_SAVE_PASSWORDS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SAVE_PASSWORDS, value).apply()
        }

    /**
     * Show open tabs as cards with page previews rather than as a list.
     *
     * Both views ship and the header switches between them, because which one
     * is right depends on how many tabs are open: previews are how you find one
     * of six, a list is how you get through thirty. Cards are the default — they
     * are what the browser has always shown and what most people recognise.
     *
     * Also a real cost switch: previews are captured only while this is on (see
     * MainActivity.captureCurrentThumbnail), so choosing the list turns off a
     * full-window bitmap grab on every pause.
     */
    var tabsGrid: Boolean
        get() = prefs.getBoolean(KEY_TABS_GRID, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TABS_GRID, value).apply()
        }

    /**
     * Default speed-dial tiles the user has removed.
     *
     * Only the built-in ones need remembering: a tile the user added is a
     * bookmark, and removing it deletes the bookmark. There's nothing to delete
     * for a default, so the fact that it's gone has to be stored somewhere.
     *
     * SharedPreferences hands back its own internal set — mutating it corrupts
     * the in-memory copy without ever reaching disk — so both accessors copy.
     */
    var hiddenQuickLinks: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_LINKS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_HIDDEN_LINKS, value.toSet()).apply()
        }

    fun hideQuickLink(url: String) {
        hiddenQuickLinks = hiddenQuickLinks + url
    }

    companion object {
        private const val FILE = "upgrid_prefs"
        private const val KEY_HIDDEN_LINKS = "hidden_quick_links"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_PLAYER_SEEK = "player_seek_seconds"
        private const val KEY_BACKGROUND_PLAYBACK = "background_playback"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_SAVE_PASSWORDS = "save_passwords"
        private const val KEY_ACCOUNT_SERVER = "account_server"
        private const val KEY_TABS_GRID = "tabs_grid"

        /**
         * The owner's own box. Reachable over TLS on the standard port; the
         * WireGuard endpoint on the same machine is UDP, so they don't collide.
         */
        const val DEFAULT_ACCOUNT_SERVER = "https://ai-game.193-160-119-15.sslip.io/upgrid"

        const val PLAYER_SEEK_DEFAULT = 10
        val PLAYER_SEEK_OPTIONS = listOf(5, 10, 15, 30)

        /**
         * Foreground syncs are throttled to this. Bookmarks are added by hand a
         * few times a day at most — anything tighter spends battery and Drive
         * quota re-uploading a document that didn't change.
         */
        const val AUTO_SYNC_INTERVAL_MS = 30 * 60 * 1000L
    }
}
