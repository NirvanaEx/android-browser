package org.mozilla.fenix.upgrid

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.Window
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.MediaSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.activity.OrientationDelegate
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.feature.media.fullscreen.MediaSessionFullscreenFeature
import mozilla.components.feature.session.ScreenOrientationFeature
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.ContinuationInterceptor

@RunWith(AndroidJUnit4::class)
class UpgridPlayerOrientationTest {
    @Test
    fun `media metadata and fullscreen exit cannot rotate the screen`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val window: Window = mockk(relaxed = true)
        every { activity.window } returns window
        val store = BrowserStore(BrowserState(tabs = listOf(createTab("https://example.org", id = "video",
            mediaSessionState = MediaSessionState(mockk(relaxed = true),
                elementMetadata = MediaSession.ElementMetadata(width = 1920, height = 1080),
                playbackState = MediaSession.PlaybackState.PLAYING, fullscreen = true))), selectedTabId = "video"))
        val feature = MediaSessionFullscreenFeature(activity, store, null,
            mainDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, autoRotate = false)
        feature.start()
        testScheduler.advanceUntilIdle()
        verify { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        store.dispatch(TabListAction.RemoveTabAction("video"))
        testScheduler.advanceUntilIdle()
        verify { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        verify(exactly = 0) { activity.requestedOrientation = any() }
        feature.stop()
    }

    @Test
    fun `website lock and unlock cannot override the active player orientation`() {
        val engine: Engine = mockk(relaxed = true)
        val activity: Activity = mockk(relaxed = true)
        var playerActive = true
        val feature = ScreenOrientationFeature(engine, activity, allowOrientationChange = { !playerActive })
        assertEquals(OrientationDelegate.LockResult.NOT_SUPPORTED,
            feature.onOrientationLock(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE))
        feature.onOrientationUnlock()
        verify(exactly = 0) { activity.requestedOrientation = any() }
        playerActive = false
        feature.onOrientationUnlock()
        verify { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}
