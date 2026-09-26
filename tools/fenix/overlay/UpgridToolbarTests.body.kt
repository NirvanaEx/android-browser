    @Test
    fun upgridBookmarkAtAddressEndAndManualTranslationOnToolbar() = runTest(testDispatcher) {
        val store = buildStore()
        val bookmark = store.state.displayState.pageActionsEnd.single() as ActionButtonRes
        assertEquals(AddBookmarkClicked(Source.AddressBar.PageEnd), bookmark.onClick)
        val translate = store.state.displayState.browserActionsEnd[1] as ActionButtonRes
        assertEquals(TranslateClicked(Source.AddressBar.BrowserEnd), translate.onClick)
    }

    @Test
    fun upgridBookmarkFollowsPageUrl() = runTest(testDispatcher) {
        val tab = createTab(url = "https://example.org", id = "upgrid-test")
        val browser = BrowserStore(BrowserState(tabs = listOf(tab), selectedTabId = tab.id))
        coEvery { bookmarksStorage.getBookmarksWithUrl("https://example.org") } returns Result.success(emptyList())
        val store = buildStore(buildMiddleware(browserStore = browser))
        assertEquals(AddBookmarkClicked(Source.AddressBar.PageEnd),
            (store.state.displayState.pageActionsEnd.single() as ActionButtonRes).onClick)
        browser.dispatch(UpdateUrlAction(tab.id, "https://mozilla.org"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(EditBookmarkClicked(Source.AddressBar.PageEnd),
            (store.state.displayState.pageActionsEnd.single() as ActionButtonRes).onClick)
    }
