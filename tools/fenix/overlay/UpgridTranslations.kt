package org.mozilla.fenix.upgrid

import android.content.Context
import android.net.ConnectivityManager
import mozilla.components.browser.state.action.TranslationsAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.translate.Language
import mozilla.components.concept.engine.translate.TranslationOperation
import java.util.Locale

/** One remembered destination; page detection and inference remain in Gecko. */
class UpgridTranslations(context: Context, private val store: BrowserStore) {
    private val preferences = context.getSharedPreferences("upgrid_translation", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private data class Page(val url: String, var loading: Boolean, var attempted: String? = null,
        var suppressed: Boolean = false, var settingsRequested: Boolean = false)
    private val pages = mutableMapOf<String, Page>()
    private var languagesRequested = false

    var automatic: Boolean
        get() = preferences.getBoolean("automatic", false)
        set(value) { preferences.edit().putBoolean("automatic", value).apply() }

    fun target(languages: List<Language>?): Language? {
        val saved = preferences.getString("target", null)
        val local = Locale.getDefault().language
        if (saved != null) return languages?.firstOrNull { it.code == saved }
        return languages?.firstOrNull { it.code == local }
            ?: languages?.firstOrNull { it.code == "en" }
            ?: languages?.firstOrNull()
    }

    fun rememberTarget(language: Language) {
        preferences.edit().putString("target", language.code).apply()
    }

    fun translateCurrentPageIfAutomatic() = onState(store.state)

    fun keepOriginal() {
        val tab = store.state.selectedTab ?: return
        val page = pages.getOrPut(tab.id) { Page(tab.content.url, tab.content.loading) }
        page.suppressed = true
    }

    /** Called on the main thread by the browser binding, once per relevant state change. */
    fun onState(state: BrowserState) {
        pages.keys.retainAll(state.tabs.map { it.id }.toSet())
        val tab = state.selectedTab ?: return
        val old = pages[tab.id]
        val page = if (old == null || old.url != tab.content.url || (!old.loading && tab.content.loading)) {
            Page(tab.content.url, tab.content.loading).also { pages[tab.id] = it }
        } else old.also { it.loading = tab.content.loading }
        val engine = state.translationEngine
        val translation = tab.translationsState
        if (!automatic || page.suppressed || tab.content.loading || tab.readerState.active ||
            !(tab.content.url.startsWith("https://") || tab.content.url.startsWith("http://")) ||
            engine.isEngineSupported != true || !engine.isTranslationsEnabled ||
            translation.isTranslated || translation.isTranslateProcessing || translation.isRestoreProcessing ||
            translation.translationError != null) return
        // Keep Android's data-saver choice effective for automatic model downloads.
        if (connectivity?.isActiveNetworkMetered == true &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return
        val detected = translation.translationEngineState?.detectedLanguages?.documentLangTag ?: return
        val languages = engine.supportedLanguages
        if (languages == null) {
            if (!languagesRequested) {
                languagesRequested = true
                store.dispatch(TranslationsAction.OperationRequestedAction(tab.id, TranslationOperation.FETCH_SUPPORTED_LANGUAGES))
            }
            return
        }
        val from = languages.fromLanguages?.firstOrNull { it.code.equals(detected, ignoreCase = true) } ?: return
        val to = target(languages.toLanguages) ?: return
        if (from.code == to.code) return
        if (translation.pageSettings == null) {
            if (!page.settingsRequested) {
                page.settingsRequested = true
                store.dispatch(TranslationsAction.OperationRequestedAction(tab.id, TranslationOperation.FETCH_PAGE_SETTINGS))
            }
            return
        }
        if (translation.pageSettings?.neverTranslateSite == true || translation.pageSettings?.neverTranslateLanguage == true) return
        val pair = from.code + ":" + to.code
        if (page.attempted == pair) return
        page.attempted = pair // Before dispatch: unrelated store ticks must not duplicate a request.
        store.dispatch(TranslationsAction.TranslateAction(tab.id, from.code, to.code, null))
    }
}
