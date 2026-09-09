package mozilla.components.lib.crash

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import mozilla.components.lib.crash.db.CrashDatabase
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgridCrashCaptureTest {
    @Test fun `capture runs when legacy prompt is disabled`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(testContext, CrashDatabase::class.java).allowMainThreadQueries().build()
        try {
            val reporter = CrashReporter(services = listOf(mock()), useLegacyReporting = false, databaseProvider = { db }, scope = this)
            val crash = Crash.UncaughtExceptionCrash(1000, IllegalStateException("test"), arrayListOf())
            var captured: Crash? = null
            reporter.onCrashCapture = { captured = it }
            reporter.onCrash(testContext, crash)
            assertEquals(crash.uuid, captured?.uuid)
            assertEquals(1, reporter.unsentCrashReportsSince(0).size)
        } finally { db.close() }
    }

    @Test fun `capture failure does not prevent existing crash persistence`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(testContext, CrashDatabase::class.java).allowMainThreadQueries().build()
        try {
            val reporter = CrashReporter(services = listOf(mock()), useLegacyReporting = false, databaseProvider = { db }, scope = this)
            reporter.onCrashCapture = { throw IllegalStateException("disk failure") }
            reporter.onCrash(testContext, Crash.UncaughtExceptionCrash(1000, IllegalStateException("original"), arrayListOf()))
            assertEquals(1, reporter.unsentCrashReportsSince(0).size)
        } finally { db.close() }
    }

    @Test fun `disabled crash reporter does not invoke capture`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(testContext, CrashDatabase::class.java).allowMainThreadQueries().build()
        try {
            val reporter = CrashReporter(services = listOf(mock()), enabled = false, useLegacyReporting = false, databaseProvider = { db }, scope = this)
            var captured = 0
            reporter.onCrashCapture = { captured++ }
            reporter.onCrash(testContext, Crash.UncaughtExceptionCrash(1000, IllegalStateException("test"), arrayListOf()))
            assertEquals(0, captured)
            assertEquals(0, reporter.unsentCrashReportsSince(0).size)
        } finally { db.close() }
    }
}
