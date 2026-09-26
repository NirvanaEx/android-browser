package org.mozilla.fenix.upgrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.support.test.fakes.engine.FakeEngine
import mozilla.components.support.test.fakes.engine.FakeEngineSessionState
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.components.appstate.AppState
import org.mozilla.fenix.components.usecases.FenixBrowserUseCases
import org.mozilla.fenix.utils.Settings

@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpgridTabsTest {
    private fun homepageUseCases(store: BrowserStore) = FenixBrowserUseCases(
        appStore = AppStore(AppState()),
        tabsUseCases = TabsUseCases(store),
        loadUrlUseCase = mockk(relaxed = true),
        searchUseCases = mockk(relaxed = true),
        homepageTitle = "Upgrid",
        profiler = null,
    )

    @Test fun `multiple empty tabs exist immediately and survive storage without an engine session`() = runTest {
        val store = BrowserStore()
        val homepage = homepageUseCases(store)
        val ids = List(3) { homepage.addNewHomepageTab(startLoading = false) }
        assertEquals(3, store.state.tabs.size)
        assertEquals(3, ids.toSet().size)
        assertEquals(ids.last(), store.state.selectedTabId)
        assertTrue(store.state.tabs.all { it.content.url == ABOUT_HOME_URL })
        assertTrue(store.state.tabs.all { it.engineState.engineSession == null })

        // Creation, switching and saving do not depend on entering an address.
        store.dispatch(TabListAction.SelectTabAction(ids.first()))
        val storage = SessionStorage(testContext, FakeEngine())
        try {
            assertTrue(storage.save(store.state))
            val restored = BrowserStore()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TabsUseCases(restored, dispatcher, dispatcher).restore(storage)
            assertEquals(ids, restored.state.tabs.map { it.id })
            assertEquals(ids.first(), restored.state.selectedTabId)
            assertTrue(restored.state.restoreComplete)
            assertTrue(restored.state.tabs.all { it.content.url == ABOUT_HOME_URL })
        } finally {
            storage.clear()
        }
    }

    @Test fun `normal page state and old tabs persist while private tabs are excluded`() = runTest {
        val engineState = FakeEngineSessionState("saved-page-history-scroll-and-form-state")
        val normal = createTab("https://example.org/page", id = "normal", createdAt = 1L, lastAccess = 1L,
            engineSessionState = engineState)
        val store = BrowserStore(BrowserState(tabs = listOf(normal), selectedTabId = normal.id))
        val homepage = homepageUseCases(store)
        val emptyId = homepage.addNewHomepageTab(startLoading = false)
        val privateId = homepage.addNewHomepageTab(private = true, startLoading = false)
        store.dispatch(TabListAction.SelectTabAction(emptyId))

        val storage = SessionStorage(testContext, FakeEngine())
        try {
            assertTrue(storage.save(store.state))
            val restored = BrowserStore()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val settings = Settings(testContext)
            // The existing manual-close default has no age-based data expiry.
            assertEquals(Long.MAX_VALUE, settings.getTabTimeout())
            TabsUseCases(restored, dispatcher, dispatcher).restore(storage, settings.getTabTimeout())
            assertEquals(listOf(normal.id, emptyId), restored.state.tabs.map { it.id })
            assertEquals(emptyId, restored.state.selectedTabId)
            assertFalse(restored.state.tabs.any { it.id == privateId || it.content.private })
            assertEquals(normal.content.url, restored.state.tabs.first().content.url)
            val restoredEngineState = restored.state.tabs.first().engineState.engineSessionState as FakeEngineSessionState
            assertEquals(engineState.value, restoredEngineState.value)
        } finally {
            storage.clear()
        }
    }
}
