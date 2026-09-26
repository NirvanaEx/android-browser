package org.mozilla.fenix.upgrid

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import mozilla.components.concept.engine.CancellableOperation
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.Action
import mozilla.components.concept.engine.webextension.ActionHandler
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpgridPlayerBridgeTest {
    @Test fun `cold start opens through connected native port without a browser action callback`() {
        val harness = BridgeHarness()
        harness.finishInstallation()
        // Fenix may have consumed the cached browser action before registering
        // this bridge's delegate. Only connectNative has announced readiness.
        harness.messages.captured.onPortConnected(harness.port)

        assertTrue(harness.bridge.requestTakeover())
        assertEquals(listOf("open"), harness.commands.map { it.getString("cmd") })
        assertEquals(listOf("opening"), harness.events.map { it.getString("t") })
        assertTrue(harness.diagnostics.isEmpty())
        verify(exactly = 1) { harness.port.postMessage(any()) }
    }

    @Test fun `tap before either channel is ready does not open or leave an opening indicator`() {
        val harness = BridgeHarness()
        assertFalse(harness.bridge.requestTakeover())
        harness.finishInstallation()
        assertFalse(harness.bridge.requestTakeover())

        assertTrue(harness.commands.isEmpty())
        assertTrue(harness.events.isEmpty())
        assertEquals(listOf("player_not_ready", "player_not_ready"),
            harness.diagnostics.map { it.first })
        verify(exactly = 0) { harness.port.postMessage(any()) }

        // The earlier taps must not prevent the next tap once startup finishes.
        harness.messages.captured.onPortConnected(harness.port)
        assertTrue(harness.bridge.requestTakeover())
        assertEquals(listOf("open"), harness.commands.map { it.getString("cmd") })
        assertEquals(listOf("opening"), harness.events.map { it.getString("t") })
    }

    @Test fun `failed native open returns false and releases the opening state with a diagnostic`() {
        val harness = BridgeHarness()
        harness.finishInstallation()
        harness.messages.captured.onPortConnected(harness.port)
        val failure = IllegalStateException("disconnected port")
        every { harness.port.postMessage(any()) } throws failure

        assertFalse(harness.bridge.requestTakeover())
        assertEquals(listOf("opening", "released"), harness.events.map { it.getString("t") })
        assertEquals(listOf("player_open"), harness.diagnostics.map { it.first })
        assertSame(failure, harness.diagnostics.single().second)
        verify(exactly = 1) { harness.port.postMessage(match { it.optString("cmd") == "open" }) }
    }

    @Test fun `browser action remains a fallback when the native port is not connected`() {
        val harness = BridgeHarness()
        harness.finishInstallation()
        var clicks = 0
        harness.announceAction { clicks++ }

        assertTrue(harness.bridge.requestTakeover())
        assertEquals(1, clicks)
        assertEquals(listOf("opening"), harness.events.map { it.getString("t") })
        assertTrue(harness.commands.isEmpty())
        assertTrue(harness.diagnostics.isEmpty())
        verify(exactly = 0) { harness.port.postMessage(any()) }
    }

    @Test fun `browser action carries tab input without sending a duplicate native request`() {
        val harness = BridgeHarness()
        harness.finishInstallation()
        var clicks = 0
        harness.announceAction { clicks++ }
        harness.messages.captured.onPortConnected(harness.port)

        assertTrue(harness.bridge.requestTakeover())
        assertEquals(1, clicks)
        assertTrue(harness.commands.isEmpty())
        assertEquals(listOf("opening"), harness.events.map { it.getString("t") })
    }

    @Test fun `cached store action remains usable when another Fenix delegate received it first`() {
        var clicks = 0
        val harness = BridgeHarness { { clicks++ } }
        harness.finishInstallation()
        harness.messages.captured.onPortConnected(harness.port)
        assertTrue(harness.bridge.requestTakeover())
        assertEquals(1, clicks)
        assertTrue(harness.commands.isEmpty())
    }

    private class BridgeHarness(storedAction: () -> (() -> Unit)? = { null }) {
        private val engine = mockk<Engine>()
        private val extension = mockk<WebExtension>()
        private val installed = slot<(WebExtension) -> Unit>()
        private val actions = slot<ActionHandler>()
        val messages = slot<MessageHandler>()
        val port = mockk<Port>()
        val commands = mutableListOf<JSONObject>()
        val events = mutableListOf<JSONObject>()
        val diagnostics = mutableListOf<Pair<String, Throwable?>>()
        val bridge = VideoPlayerBridge(engine, storedAction)

        init {
            every { engine.installBuiltInWebExtension(VideoPlayerBridge.EXTENSION_ID,
                VideoPlayerBridge.EXTENSION_URL, capture(installed), any()) } returns CancellableOperation.Noop()
            every { extension.id } returns VideoPlayerBridge.EXTENSION_ID
            every { extension.registerActionHandler(capture(actions)) } returns Unit
            every { extension.registerBackgroundMessageHandler("upgridPlayer", capture(messages)) } returns Unit
            every { port.postMessage(capture(commands)) } returns Unit
            bridge.onPlayerEvent = { events.add(it) }
            bridge.onDiagnostic = { code, error -> diagnostics.add(code to error) }
            bridge.setupAndInstall()
        }

        fun finishInstallation() = installed.captured.invoke(extension)

        fun announceAction(onClick: () -> Unit) {
            actions.captured.onBrowserAction(extension, null, Action(
                title = null, enabled = true, loadIcon = null, badgeText = null,
                badgeTextColor = null, badgeBackgroundColor = null, onClick = onClick,
            ))
        }
    }
}
