package org.mozilla.fenix.upgrid

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.state.state.createTab
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.top.sites.PinnedSiteStorage
import mozilla.components.support.test.fakes.engine.TestEngineSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.components.menu.fake.FakeBookmarksStorage
import org.mozilla.fenix.components.menu.middleware.MenuDialogMiddleware
import org.mozilla.fenix.components.menu.store.BrowserMenuState
import org.mozilla.fenix.components.menu.store.MenuState
import org.mozilla.fenix.components.menu.store.MenuStore
import org.mozilla.fenix.settings.summarize.FakeSummarizationFeatureConfiguration
import org.mozilla.fenix.summarization.eligibility.SummarizationEligibilityChecker
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpgridMenuTest {
    @Test fun `compact menu preserves bookmarks and extensions without hidden feature queries`() = runTest {
        val bookmarks = FakeBookmarksStorage()
        val url = "https://example.org"
        val guid = bookmarks.addItem(BookmarkRoot.Mobile.id, url, "Example", 0u).getOrThrow()
        val addons = mockk<AddonManager>()
        coEvery { addons.getAddons() } returns emptyList()
        // Strict mocks fail the test if either hidden feature is consulted.
        val pinned = mockk<PinnedSiteStorage>()
        val summarization = mockk<SummarizationEligibilityChecker>()
        val middleware = MenuDialogMiddleware(
            appStore = AppStore(), addonManager = addons, settings = mockk(relaxed = true),
            summarizeMenuSettings = FakeSummarizationFeatureConfiguration(),
            summarizationEligibilityChecker = summarization,
            bookmarksStorage = bookmarks, pinnedSiteStorage = pinned,
            appLinksUseCases = mockk(), addBookmarkUseCase = mockk(),
            addPinnedSiteUseCase = mockk(), removePinnedSitesUseCase = mockk(),
            requestDesktopSiteUseCase = mockk(), migratePrivateTabUseCase = mockk(),
            materialAlertDialogBuilder = mockk(), topSitesMaxLimit = 16,
            onDeleteAndQuit = {}, onDismiss = {}, onSendPendingIntentWithUrl = { _, _ -> },
            mainDispatcher = StandardTestDispatcher(testScheduler), upgridCompactMenu = true,
        )
        val store = MenuStore(
            initialState = MenuState(browserMenuState = BrowserMenuState(
                selectedTab = createTab(url = url, engineSession = TestEngineSession()),
            )),
            middleware = listOf(middleware),
        )
        testScheduler.advanceUntilIdle()
        assertTrue(store.state.browserMenuState!!.bookmarkState.isBookmarked)
        assertEquals(guid, store.state.browserMenuState!!.bookmarkState.guid)
        coVerify(exactly = 1) { addons.getAddons() }
        coVerify(exactly = 0) { pinned.getPinnedSites() }
        coVerify(exactly = 0) { summarization.checkLanguage(any()) }
    }
}
