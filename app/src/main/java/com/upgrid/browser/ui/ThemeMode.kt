package com.upgrid.browser.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.upgrid.browser.R
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme

/**
 * Light / dark / follow-the-system, for both halves of the browser.
 *
 * Two things have to agree here, and they're set through completely unrelated
 * APIs. [nightMode] drives the app's own chrome through AppCompat, which swaps
 * `values/` for `values-night/` and recreates the visible activities.
 * [colorScheme] drives what web pages see through `prefers-color-scheme` — miss
 * that one and a user on the dark theme still gets a white flash out of every
 * site that supports dark mode.
 */
enum class ThemeMode(
    val key: String,
    @StringRes val label: Int,
    val nightMode: Int,
    val colorScheme: PreferredColorScheme,
) {
    SYSTEM(
        "system",
        R.string.settings_theme_system,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        PreferredColorScheme.System,
    ),
    LIGHT(
        "light",
        R.string.settings_theme_light,
        AppCompatDelegate.MODE_NIGHT_NO,
        PreferredColorScheme.Light,
    ),
    DARK(
        "dark",
        R.string.settings_theme_dark,
        AppCompatDelegate.MODE_NIGHT_YES,
        PreferredColorScheme.Dark,
    );

    companion object {
        val DEFAULT = SYSTEM

        fun fromKeyOrDefault(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: DEFAULT

        /**
         * Apply a theme to the app chrome.
         *
         * Safe to call repeatedly and from anywhere: AppCompat no-ops when the
         * mode hasn't changed, so this is cheap on every start and recreates
         * activities only when the user actually picks something new.
         */
        fun apply(mode: ThemeMode) {
            AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        }
    }
}

/**
 * Tell the engine which colour scheme pages should render for.
 *
 * Kept out of [ThemeMode.apply] because it needs the engine: applying the app
 * theme has to work anywhere, but touching `Engine.settings` before the Gecko
 * runtime exists would force it into being early.
 */
fun Engine.applyColorScheme(mode: ThemeMode) {
    settings.preferredColorScheme = mode.colorScheme
}
