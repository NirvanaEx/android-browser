package org.mozilla.fenix.upgrid

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import mozilla.components.concept.engine.EngineSession
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction.CommitUrl
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.mozilla.fenix.AboutHomeBinding
import org.mozilla.fenix.AppRequestInterceptor
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.browser.browsingmode.BrowsingModeManager
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.usecases.FenixBrowserUseCases
import org.mozilla.fenix.helpers.FenixGleanTestRule
import org.mozilla.fenix.search.BrowserToolbarSearchMiddleware
import org.mozilla.fenix.utils.Settings

@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpgridHomeRoutingTest {
    @get:Rule
    val gleanTestRule = FenixGleanTestRule(testContext)

    private val pageUrl = "http://127.0.0.1:8766/aspect.html?probe=1"

    private fun navigator(destinationId: () -> Int): NavController {
        val destination: NavDestination = mockk()
        every { destination.id } answers { destinationId() }
        return mockk<NavController>(relaxed = true).also {
            every { it.currentDestination } returns destination
        }
    }

    private fun lateHomeRequest(nav: NavController) {
        val interceptor = AppRequestInterceptor(testContext).also { it.setNavigationController(nav) }
        assertNull(interceptor.onLoadRequest(
            engineSession = mockk(), uri = ABOUT_HOME_URL, lastUri = null,
            hasUserGesture = false, isSameDomain = false, isRedirect = false,
            isDirectNavigation = false, isSubframeRequest = false,
        ))
    }

    @Test fun `real search commit keeps browser open before Gecko acknowledges the new URL`() = runTest {
        val engine: EngineSession = mockk(relaxed = true)
        val selected = createTab(ABOUT_HOME_URL, id = "selected", engineSession = engine)
        val empty = createTab(ABOUT_HOME_URL, id = "background-empty")
        val store = BrowserStore(BrowserState(tabs = listOf(selected, empty), selectedTabId = selected.id))
        var destination = R.id.homeFragment
        val nav = navigator { destination }
        every { nav.navigate(NavGraphDirections.actionGlobalBrowser()) } answers { destination = R.id.browserFragment }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appStore = AppStore()
        val useCases = FenixBrowserUseCases(
            appStore = appStore,
            tabsUseCases = TabsUseCases(store),
            loadUrlUseCase = SessionUseCases(store).loadUrl,
            searchUseCases = mockk(relaxed = true),
            homepageTitle = "Upgrid", profiler = null,
            onLoadStarted = { input -> UpgridHomeNavigation.onLoadStarted(store, input) },
        )
        val components: Components = mockk(relaxed = true)
        every { components.useCases.fenixBrowserUseCases } returns useCases
        val settings: Settings = mockk(relaxed = true) {
            every { enableHomepageAsNewTab } returns true
        }
        val mode: BrowsingModeManager = mockk(relaxed = true)
        every { mode.mode } returns BrowsingMode.Normal
        val toolbar = BrowserToolbarStore(middleware = listOf(BrowserToolbarSearchMiddleware(
            uiContext = testContext, appStore = appStore, browserStore = store, components = components,
            navController = nav, browsingModeManager = mode, settings = settings,
            scope = backgroundScope, autocompleteDispatcher = dispatcher,
        )))
        val binding = AboutHomeBinding(store, nav, dispatcher)
        // Queue the binding's first about:home snapshot, then submit using the real
        // search middleware -> FenixBrowserUseCases -> SessionUseCases -> engine path.
        binding.start()
        try {
            toolbar.dispatch(CommitUrl(pageUrl))
            assertEquals(R.id.browserFragment, destination)
            assertEquals(ABOUT_HOME_URL, store.state.tabs.first().content.url)
            verify(exactly = 1) { engine.loadUrl(url = pageUrl, originalInput = pageUrl, flags = any(), additionalHeaders = any()) }
            runCurrent()
            lateHomeRequest(nav)
            runCurrent()
            assertEquals(selected.id, store.state.selectedTabId)
            assertEquals(ABOUT_HOME_URL, store.state.tabs.first().content.url)
            verify(exactly = 0) { nav.navigate(NavGraphDirections.actionGlobalHome()) }

            // Only now deliver Gecko's delayed location acknowledgement. A later
            // intentional home navigation must still work for this same tab ID.
            store.dispatch(ContentAction.UpdateUrlAction(selected.id, pageUrl))
            runCurrent()
            store.dispatch(ContentAction.UpdateUrlAction(selected.id, ABOUT_HOME_URL))
            runCurrent()
            verify(exactly = 1) { nav.navigate(NavGraphDirections.actionGlobalHome()) }
        } finally {
            binding.stop()
        }
    }

    @Test fun `explicit home command and backgrounding clear a pending home departure`() = runTest {
        val tab = createTab(ABOUT_HOME_URL, id = "home")
        val store = BrowserStore(BrowserState(tabs = listOf(tab), selectedTabId = tab.id))
        UpgridHomeNavigation.onLoadStarted(store, pageUrl)
        org.junit.Assert.assertFalse(UpgridHomeNavigation.shouldShowHome(store))
        UpgridHomeNavigation.onLoadStarted(store, ABOUT_HOME_URL)
        org.junit.Assert.assertTrue(UpgridHomeNavigation.shouldShowHome(store))
        UpgridHomeNavigation.onLoadStarted(store, pageUrl)
        AboutHomeBinding(store, navigator { R.id.homeFragment }).stop()
        org.junit.Assert.assertTrue(UpgridHomeNavigation.shouldShowHome(store))
    }

    @Test fun `another empty tab can be selected while the first still awaits its new location`() = runTest {
        val first = createTab(ABOUT_HOME_URL, id = "pending")
        val second = createTab(ABOUT_HOME_URL, id = "other-home")
        val store = BrowserStore(BrowserState(tabs = listOf(first, second), selectedTabId = first.id))
        val nav = navigator { R.id.browserFragment }
        UpgridHomeNavigation.onLoadStarted(store, pageUrl)
        val binding = AboutHomeBinding(store, nav, StandardTestDispatcher(testScheduler))
        binding.start()
        try {
            runCurrent()
            verify(exactly = 0) { nav.navigate(NavGraphDirections.actionGlobalHome()) }
            store.dispatch(TabListAction.SelectTabAction(second.id))
            runCurrent()
            verify(exactly = 1) { nav.navigate(NavGraphDirections.actionGlobalHome()) }
        } finally {
            binding.stop()
        }
    }

    @Test fun `background empty-tab loads do not dismiss the tabs tray`() {
        val nav = navigator { R.id.tabManagementFragment }
        lateHomeRequest(nav)
        verify(exactly = 0) { nav.navigate(NavGraphDirections.actionGlobalHome()) }
    }

    @Test fun `selecting an empty tab still opens home through selected tab state`() = runTest {
        val selected = createTab(pageUrl, id = "selected")
        val empty = createTab(ABOUT_HOME_URL, id = "empty")
        val store = BrowserStore(BrowserState(tabs = listOf(selected, empty), selectedTabId = selected.id))
        val nav = navigator { R.id.browserFragment }
        val binding = AboutHomeBinding(store, nav, StandardTestDispatcher(testScheduler))
        binding.start()
        try {
            runCurrent()
            store.dispatch(TabListAction.SelectTabAction(empty.id))
            runCurrent()
            assertEquals(empty.id, store.state.selectedTabId)
            verify(exactly = 1) { nav.navigate(NavGraphDirections.actionGlobalHome()) }
        } finally {
            binding.stop()
        }
    }

    @Test fun `queued home state cannot undo a newer selected page`() = runTest {
        val home = createTab(ABOUT_HOME_URL, id = "selected")
        val stale = BrowserState(tabs = listOf(home), selectedTabId = home.id)
        val store = BrowserStore(stale)
        val nav = navigator { R.id.browserFragment }
        store.dispatch(ContentAction.UpdateUrlAction(home.id, pageUrl))
        AboutHomeBinding(store, nav, StandardTestDispatcher(testScheduler)).onState(flowOf(stale))
        verify(exactly = 0) { nav.navigate(NavGraphDirections.actionGlobalHome()) }
    }
}
