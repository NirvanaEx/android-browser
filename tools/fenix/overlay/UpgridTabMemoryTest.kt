package org.mozilla.fenix.upgrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.MediaSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpgridTabMemoryTest {
    private fun tabs() = (0..7).map { index ->
        val engine: EngineSession = mockk(relaxed = true)
        var observer: EngineSession.Observer? = null
        every { engine.register(any()) } answers { observer = firstArg() }
        every { engine.checkForFormData(false) } answers { observer?.onCheckForFormData(false, false) }
        createTab("https://example.org/$index", id = "$index", createdAt = 1L,
            lastAccess = 1_000L + index, lastVisibleAt = 1_000L + index,
            engineSession = engine, engineSessionState = mockk(relaxed = true))
    }

    @Test fun `retain five recent tabs and require thirty minutes away`() {
        val state = BrowserState(tabs = tabs(), selectedTabId = "7")
        assertEquals(listOf("0", "1", "2"), UpgridTabMemory.candidates(state, 2_000_000).map { it.id })
        assertTrue(UpgridTabMemory.candidates(state, 30_000).isEmpty())
        assertTrue(UpgridTabMemory.candidates(state, 61_000).isEmpty())
        assertTrue(UpgridTabMemory.candidates(state, 1_800_999).isEmpty())
        assertEquals(listOf("0"), UpgridTabMemory.candidates(state, 1_801_000).map { it.id })
        assertTrue(UpgridTabMemory.candidates(state.copy(tabs = state.tabs.take(5)), 2_000_000).isEmpty())
        val recent = state.tabs.map { if (it.id == "0") it.copy(lastVisibleAt = 1_999_000) else it }
        assertFalse(UpgridTabMemory.candidates(state.copy(tabs = recent), 2_000_000).any { it.id == "0" })
    }

    @Test fun `never suspend forms private pages media loading or unrestorable tabs`() {
        val original = tabs()
        val old = original[0]
        val protected = listOf(
            old.copy(content = old.content.copy(hasFormData = true)),
            old.copy(content = old.content.copy(private = true)),
            old.copy(content = old.content.copy(loading = true)),
            old.copy(content = old.content.copy(fullScreen = true)),
            old.copy(content = old.content.copy(pictureInPictureEnabled = true)),
            old.copy(content = old.content.copy(url = "file:///local")),
            old.copy(engineState = old.engineState.copy(engineSessionState = null)),
            old.copy(mediaSessionState = MediaSessionState(mockk(relaxed = true))),
            old.copy(lastMediaAccessState = old.lastMediaAccessState.copy(lastMediaUrl = old.content.url)),
            old.copy(content = old.content.copy(promptRequests = listOf(mockk(relaxed = true)))),
        )
        protected.forEach { tab ->
            val state = BrowserState(tabs = listOf(tab) + original.drop(1), selectedTabId = "7")
            assertFalse(UpgridTabMemory.candidates(state, 2_000_000).any { it.id == "0" })
        }
        val download = DownloadState("https://example.org/file", sessionId = "0")
        val state = BrowserState(tabs = original, selectedTabId = "7", downloads = mapOf(download.id to download))
        assertFalse(UpgridTabMemory.candidates(state, 2_000_000).any { it.id == "0" })
    }

    @Test fun `actual engine middleware preserves session state and never deletes a tab`() = runTest {
        val original = tabs()
        val store = BrowserStore(BrowserState(tabs = original, selectedTabId = "7"),
            middleware = EngineMiddleware.create(mockk(relaxed = true), scope = this, trimMemoryAutomatically = false))
        val memory = UpgridTabMemory(store, this) { 2_000_000 }
        repeat(3) { assertTrue(memory.trimOne()) }
        assertFalse(memory.trimOne())
        advanceUntilIdle()
        assertEquals(8, store.state.tabs.size)
        assertEquals(5, store.state.tabs.count { it.engineState.engineSession != null })
        for (index in 0..2) {
            assertSame(original[index].engineState.engineSessionState, store.state.tabs[index].engineState.engineSessionState)
            verify(exactly = 1) { original[index].engineState.engineSession!!.close() }
        }
        verify(exactly = 0) { original[7].engineState.engineSession!!.close() }
    }

    @Test fun `recheck selection at trim time and stop work when app backgrounds`() = runTest {
        val original = tabs()
        val store = BrowserStore(BrowserState(tabs = original, selectedTabId = "7"),
            middleware = EngineMiddleware.create(mockk(relaxed = true), scope = this, trimMemoryAutomatically = false))
        val memory = UpgridTabMemory(store, this) { 2_000_000 }
        val owner: androidx.lifecycle.LifecycleOwner = mockk(relaxed = true)
        memory.onStart(owner)
        memory.onStart(owner)
        store.dispatch(TabListAction.SelectTabAction("0"))
        advanceTimeBy(15_001)
        runCurrent()
        assertSame(original[0].engineState.engineSession, store.state.tabs[0].engineState.engineSession)
        val count = store.state.tabs.count { it.engineState.engineSession != null }
        assertEquals(7, count)
        memory.onStop(owner)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(count, store.state.tabs.count { it.engineState.engineSession != null })
    }

    @Test fun `form check timeout leaves the page loaded`() = runTest {
        val original = tabs()
        every { original[0].engineState.engineSession!!.checkForFormData(false) } returns Unit
        val store = BrowserStore(BrowserState(tabs = original, selectedTabId = "7"),
            middleware = EngineMiddleware.create(mockk(relaxed = true), scope = this, trimMemoryAutomatically = false))
        assertFalse(UpgridTabMemory(store, this) { 2_000_000 }.trimOne())
        assertNotNull(store.state.tabs[0].engineState.engineSession)
        verify(exactly = 0) { original[0].engineState.engineSession!!.close() }
    }

    @Test fun `fresh form data or reselection during the form query prevents suspension`() = runTest {
        for (form in listOf(true, false)) {
            val original = tabs()
            val engine = original[0].engineState.engineSession!!
            val store = BrowserStore(BrowserState(tabs = original, selectedTabId = "7"),
                middleware = EngineMiddleware.create(mockk(relaxed = true), scope = this, trimMemoryAutomatically = false))
            var observer: EngineSession.Observer? = null
            every { engine.register(any()) } answers { observer = firstArg() }
            every { engine.checkForFormData(false) } answers {
                if (!form) store.dispatch(TabListAction.SelectTabAction("0"))
                observer!!.onCheckForFormData(form, false)
            }
            assertFalse(UpgridTabMemory(store, this) { 2_000_000 }.trimOne())
            assertSame(engine, store.state.tabs[0].engineState.engineSession)
            verify(exactly = 0) { engine.close() }
        }
    }
}
