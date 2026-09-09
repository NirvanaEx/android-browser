package mozilla.components.support.webextensions

import androidx.test.ext.junit.runners.AndroidJUnit4
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionDelegate
import mozilla.components.support.test.argumentCaptor
import mozilla.components.support.test.middleware.CaptureActionsMiddleware
import mozilla.components.support.test.mock
import mozilla.components.support.test.whenever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
class UpgridPermissionTest {
    private val uboId = "uBlock0@raymondhill.net"

    @Test
    fun `only pinned uBO receives automatic install permission`() {
        for (id in listOf(uboId, "custom@example.org")) {
            val capture = CaptureActionsMiddleware<BrowserState, BrowserAction>()
            val engine: Engine = mock()
            val extension: WebExtension = mock()
            whenever(extension.id).thenReturn(id)
            val delegate = argumentCaptor<WebExtensionDelegate>()
            WebExtensionSupport.initialize(engine, BrowserStore(middleware = listOf(capture)),
                autoGrantedExtensionIds = setOf(uboId))
            verify(engine).registerWebExtensionDelegate(delegate.capture())
            var answer: PermissionPromptResponse? = null
            delegate.value.onInstallPermissionRequest(extension, listOf("tabs"), listOf("<all_urls>"),
                emptyList()) { answer = it }
            if (id == uboId) {
                assertEquals(PermissionPromptResponse(true, true, true), answer)
            } else {
                assertNull(answer)
                capture.assertLastAction(WebExtensionAction.UpdatePromptRequestWebExtensionAction::class) {}
            }
        }
    }

    @Test
    fun `custom extension updates retain the normal permission callback`() {
        val engine: Engine = mock()
        val extension: WebExtension = mock()
        whenever(extension.id).thenReturn("custom@example.org")
        val delegate = argumentCaptor<WebExtensionDelegate>()
        var forwarded = false
        WebExtensionSupport.initialize(engine, BrowserStore(), autoGrantedExtensionIds = setOf(uboId),
            onUpdatePermissionRequest = { _, _, _, _, confirm -> forwarded = true; confirm(false) })
        verify(engine).registerWebExtensionDelegate(delegate.capture())
        var allowed: Boolean? = null
        delegate.value.onUpdatePermissionRequest(extension, listOf("tabs"), emptyList(), emptyList()) { allowed = it }
        assertEquals(true, forwarded)
        assertEquals(false, allowed)
    }

    @Test
    fun `custom optional permissions still require the standard prompt`() {
        val capture = CaptureActionsMiddleware<BrowserState, BrowserAction>()
        val engine: Engine = mock()
        val extension: WebExtension = mock()
        whenever(extension.id).thenReturn("custom@example.org")
        val delegate = argumentCaptor<WebExtensionDelegate>()
        WebExtensionSupport.initialize(engine, BrowserStore(middleware = listOf(capture)),
            autoGrantedExtensionIds = setOf(uboId))
        verify(engine).registerWebExtensionDelegate(delegate.capture())
        var allowed: Boolean? = null
        delegate.value.onOptionalPermissionsRequest(extension, listOf("tabs"), emptyList(), emptyList()) { allowed = it }
        assertNull(allowed)
        capture.assertLastAction(WebExtensionAction.UpdatePromptRequestWebExtensionAction::class) {}
    }
}
