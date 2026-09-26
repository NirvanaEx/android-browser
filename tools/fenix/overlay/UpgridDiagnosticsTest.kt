package org.mozilla.fenix.upgrid

import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mozilla.components.lib.crash.Crash
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpgridDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val io = mockk<ScheduledExecutorService>(relaxed = true)
    private val tasks = mutableListOf<Runnable>()
    private val scheduled = mutableListOf<Runnable>()
    private lateinit var diagnostics: UpgridDiagnostics
    private val root get() = File(temporary.root, "upgrid-diagnostics")

    @Before fun setup() {
        every { context.noBackupFilesDir } returns temporary.root
        every { io.execute(capture(tasks)) } answers { }
        every { io.schedule(capture(scheduled), any<Long>(), any<TimeUnit>()) } returns mockk(relaxed = true)
        diagnostics = UpgridDiagnostics(context, collectionEnabled = true, io = io)
    }

    private fun drain() {
        while (tasks.isNotEmpty()) tasks.removeAt(0).run()
    }

    private fun reports() = File(root, "queue").listFiles().orEmpty()
        .filter { it.extension == "json" }.map { JSONObject(it.readText()) }

    @Test fun `UI callbacks do not access storage or traverse exception stacks`() {
        val failure = mockk<Throwable>(relaxed = true)
        diagnostics.breadcrumb("menu_open")
        diagnostics.error("ui_warning", failure)
        verify(exactly = 0) { context.noBackupFilesDir }
        verify(exactly = 0) { failure.stackTrace }
        assertFalse(root.exists())
        assertEquals(1, tasks.size)
        assertEquals(1, scheduled.size)
    }

    @Test fun `navigation bursts persist one bounded latest snapshot`() {
        repeat(100) { diagnostics.breadcrumb("fragment_$it") }
        assertEquals(1, scheduled.size)
        assertEquals(0, tasks.size)
        scheduled.removeAt(0).run()
        val saved = org.json.JSONArray(File(root, "breadcrumbs.json").readText())
        assertEquals(40, saved.length())
        assertEquals("fragment_60", saved.getJSONObject(0).getString("action"))
        assertEquals("fragment_99", saved.getJSONObject(39).getString("action"))
        diagnostics.breadcrumb("menu_open")
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).run()
        assertTrue(File(root, "breadcrumbs.json").readText().contains("menu_open"))
    }

    @Test fun `deferred errors retain event breadcrumbs and strip private messages`() {
        diagnostics.breadcrumb("before_error")
        diagnostics.error("test", IllegalStateException("DO_NOT_UPLOAD https://example.invalid/secret"))
        diagnostics.breadcrumb("after_error")
        drain()
        val saved = reports().single()
        assertEquals("handled_error", saved.getString("kind"))
        assertEquals(1, saved.getJSONArray("breadcrumbs").length())
        assertEquals("before_error", saved.getJSONArray("breadcrumbs").getJSONObject(0).getString("action"))
        assertFalse(saved.toString().contains("DO_NOT_UPLOAD"))
        assertFalse(saved.toString().contains("example.invalid"))
        assertTrue(saved.getJSONArray("exceptions").length() > 0)
    }

    @Test fun `error storms stay bounded while IO is blocked and deduplicate when drained`() {
        val failure = IllegalStateException("repeated")
        repeat(1000) { diagnostics.error("same", failure) }
        assertEquals(64, tasks.size)
        assertFalse(root.exists())
        drain()
        assertEquals(1, reports().size)
    }

    @Test fun `crashes persist synchronously before background work starts`() {
        diagnostics.breadcrumb("menu_open")
        val crash = Crash.UncaughtExceptionCrash(1000, IllegalStateException("fatal"), arrayListOf())
        assertTrue(diagnostics.recordCrash(crash))
        val saved = reports().single()
        assertEquals(crash.uuid, saved.getString("id"))
        assertEquals("menu_open", saved.getJSONArray("breadcrumbs").getJSONObject(0).getString("action"))
        assertEquals(0, tasks.size)
        assertEquals(1, scheduled.size)
        // A service in a later process must retain the same installation identity.
        val restarted = UpgridDiagnostics(context, collectionEnabled = true, io = io)
        val second = Crash.UncaughtExceptionCrash(2000, IllegalStateException("later"), arrayListOf())
        assertTrue(restarted.recordCrash(second))
        assertEquals(1, reports().map { it.getString("installation") }.toSet().size)
    }
}
