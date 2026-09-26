package org.mozilla.fenix.upgrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.spyk
import io.mockk.verify
import mozilla.components.browser.state.action.TranslationsAction
import mozilla.components.browser.state.state.*
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.translate.*
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgridTranslationsTest {
    private val ru = Language("ru", "Русский")
    private val en = Language("en", "English")
    private val de = Language("de", "Deutsch")
    private lateinit var store: BrowserStore
    private lateinit var controller: UpgridTranslations
    private var state = page("en")
    private fun page(code: String, id: String = "tab", url: String = "https://example.org/") = BrowserState(
        tabs = listOf(createTab(url, id = id).copy(translationsState = TranslationsState(
            translationEngineState = TranslationEngineState(detectedLanguages = DetectedLanguages(code, true, "en")),
            pageSettings = TranslationPageSettings(neverTranslateSite = false, neverTranslateLanguage = false),
        ))), selectedTabId = id,
        translationEngine = TranslationsBrowserState(isEngineSupported = true,
            supportedLanguages = TranslationSupport(listOf(en, de, ru), listOf(en, de, ru))),
    )
    @Before fun setup() {
        testContext.getSharedPreferences("upgrid_translation", 0).edit().clear().commit()
        store = spyk(BrowserStore(state))
        controller = UpgridTranslations(testContext, store)
        controller.rememberTarget(ru)
    }
    @Test fun `destination survives a new controller`() {
        assertEquals(ru, UpgridTranslations(testContext, store).target(listOf(en, ru)))
    }
    @Test fun `unavailable saved destination is never silently replaced`() {
        assertNull(controller.target(listOf(en, de)))
    }
    @Test fun `manual mode never starts a translation`() {
        controller.onState(state)
        verify(exactly = 0) { store.dispatch(any()) }
    }
    @Test fun `all supported foreign languages use one saved destination once per page`() {
        controller.automatic = true
        repeat(10) { controller.onState(state) }
        controller.onState(page("de", url = "https://example.org/german"))
        verify(exactly = 1) { store.dispatch(TranslationsAction.TranslateAction("tab", "en", "ru", null)) }
        verify(exactly = 1) { store.dispatch(TranslationsAction.TranslateAction("tab", "de", "ru", null)) }
    }
    @Test fun `same and unsupported languages are skipped`() {
        controller.automatic = true
        controller.onState(page("ru")); controller.onState(page("xx"))
        verify(exactly = 0) { store.dispatch(any()) }
    }
    @Test fun `restoring original suppresses only this document`() {
        controller.automatic = true
        controller.keepOriginal()
        controller.onState(state)
        verify(exactly = 0) { store.dispatch(any()) }
        controller.onState(page("de", url = "https://example.org/next"))
        verify(exactly = 1) { store.dispatch(TranslationsAction.TranslateAction("tab", "de", "ru", null)) }
    }
    @Test fun `new tab with same URL is independent`() {
        controller.automatic = true
        controller.onState(state)
        controller.onState(page("en", id = "other"))
        verify(exactly = 1) { store.dispatch(TranslationsAction.TranslateAction("other", "en", "ru", null)) }
    }
    @Test fun `never translate site and language are respected`() {
        controller.automatic = true
        for (settings in listOf(TranslationPageSettings(neverTranslateSite = true), TranslationPageSettings(neverTranslateLanguage = true))) {
            val tab = state.tabs[0]
            controller.onState(state.copy(tabs = listOf(tab.copy(translationsState = tab.translationsState.copy(pageSettings = settings)))))
        }
        verify(exactly = 0) { store.dispatch(any()) }
    }
    @Test fun `page settings are fetched once before automatic translation`() {
        controller.automatic = true
        val tab = state.tabs[0]
        val pending = state.copy(tabs = listOf(tab.copy(translationsState = tab.translationsState.copy(pageSettings = null))))
        repeat(10) { controller.onState(pending) }
        verify(exactly = 1) { store.dispatch(TranslationsAction.OperationRequestedAction("tab", TranslationOperation.FETCH_PAGE_SETTINGS)) }
        controller.onState(state)
        verify(exactly = 1) { store.dispatch(TranslationsAction.TranslateAction("tab", "en", "ru", null)) }
    }
    @Test fun `loading and reader mode are not translated`() {
        controller.automatic = true
        val tab = state.tabs[0]
        controller.onState(state.copy(tabs = listOf(tab.copy(content = tab.content.copy(loading = true)))))
        controller.onState(state.copy(tabs = listOf(tab.copy(readerState = tab.readerState.copy(active = true)))))
        verify(exactly = 0) { store.dispatch(any()) }
    }
    @Test fun `reload of the same URL allows a fresh translation`() {
        controller.automatic = true
        controller.onState(state)
        val tab = state.tabs[0]
        controller.onState(state.copy(tabs = listOf(tab.copy(content = tab.content.copy(loading = true)))))
        controller.onState(state)
        verify(exactly = 2) { store.dispatch(TranslationsAction.TranslateAction("tab", "en", "ru", null)) }
    }
}
