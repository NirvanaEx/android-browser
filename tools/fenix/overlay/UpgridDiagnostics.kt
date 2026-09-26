package org.mozilla.fenix.upgrid

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import mozilla.components.concept.base.crash.Breadcrumb
import mozilla.components.lib.crash.Crash
import mozilla.components.lib.crash.CrashReporter
import mozilla.components.lib.crash.service.CrashReporterService
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.sink.LogSink
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/** Bounded technical reports. No browsing URLs, titles, form values or raw logcat. */
class UpgridDiagnostics internal constructor(
    private val context: Context,
    private val collectionEnabled: Boolean = enabled,
    private val io: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "UpgridDiagnosticsIO").apply { isDaemon = true }
    },
) : LogSink {
    // Construction and ordinary UI callbacks must not touch storage. Crash capture
    // can still initialize it synchronously if the process fails before IO starts.
    private val root by lazy { File(context.noBackupFilesDir, "upgrid-diagnostics").apply { mkdirs() } }
    private val queue by lazy { File(root, "queue").apply { mkdirs() } }
    private val queueMutex = Any()
    private val recent = ArrayDeque<JSONObject>()
    private val signatures = LinkedHashMap<String, Long>()
    private val started = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val fatalRecorded = AtomicBoolean(false)
    private val pendingTasks = java.util.concurrent.atomic.AtomicInteger()
    private val breadcrumbRevision = AtomicLong()
    private val breadcrumbWritePending = AtomicBoolean(false)
    private val heartbeat = AtomicLong(SystemClock.uptimeMillis())
    @Volatile private var foreground = 0
    private val installation: String by lazy { runCatching { locked {
        val file = File(root, "installation")
        file.takeIf { it.isFile }?.readText()?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: UUID.randomUUID().toString().also { file.writeText(it) }
    } }.getOrElse { UUID.randomUUID().toString() } }

    private fun <T> locked(block: () -> T): T = synchronized(queueMutex) {
        RandomAccessFile(File(root, "queue.lock"), "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }

    private fun background(block: () -> Unit) {
        if (pendingTasks.incrementAndGet() > 64) { pendingTasks.decrementAndGet(); return }
        io.execute {
            try { block() } catch (_: Exception) {
                android.util.Log.w("UpgridDiagnostics", "Diagnostic operation failed; browser continues")
            } finally { pendingTasks.decrementAndGet() }
        }
    }

    fun breadcrumb(name: String) {
        if (!collectionEnabled) return
        synchronized(recent) {
            if (recent.size == 40) recent.removeFirst()
            recent.addLast(JSONObject().put("action", label(name)).put("uptime", SystemClock.uptimeMillis()))
        }
        breadcrumbRevision.incrementAndGet()
        scheduleBreadcrumbWrite()
    }

    private fun scheduleBreadcrumbWrite() {
        if (!breadcrumbWritePending.compareAndSet(false, true)) return
        // A navigation can open several fragments in one frame. Persist the newest
        // bounded snapshot once, instead of queuing a disk write for every callback.
        io.schedule({
            val revision = breadcrumbRevision.get()
            try {
                val snapshot = breadcrumbs().toString()
                locked { File(root, "breadcrumbs.json").writeText(snapshot) }
            } catch (_: Exception) {
                android.util.Log.w("UpgridDiagnostics", "Breadcrumb persistence failed; browser continues")
            } finally {
                breadcrumbWritePending.set(false)
                if (breadcrumbRevision.get() != revision) scheduleBreadcrumbWrite()
            }
        }, 250, TimeUnit.MILLISECONDS)
    }

    private fun recentBreadcrumbs(): JSONArray = synchronized(recent) { JSONArray(recent.toList()) }

    private fun breadcrumbs(): JSONArray {
        val snapshot = recentBreadcrumbs()
        // Never hold the UI's in-memory lock while accessing the filesystem.
        return if (snapshot.length() > 0) snapshot else runCatching {
            JSONArray(File(root, "breadcrumbs.json").readText())
        }.getOrDefault(JSONArray())
    }

    private fun exceptions(throwable: Throwable?): JSONArray {
        val result = JSONArray()
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var current = throwable
        while (current != null && result.length() < 8 && seen.put(current, true) == null) {
            result.put(JSONObject().put("type", current.javaClass.name)
                .put("frames", JSONArray(current.stackTrace.take(60).map { frame(it) })))
            current = current.cause
        }
        return result
    }

    private fun report(kind: String, source: String, cause: Throwable? = null, details: JSONObject = JSONObject(),
                       id: String = UUID.randomUUID().toString(), timestamp: Long = System.currentTimeMillis(),
                       actions: JSONArray = breadcrumbs()): JSONObject =
        JSONObject().put("schema", 1).put("id", id).put("installation", installation)
            .put("build", BuildConfig.VERSION_NAME).put("timestamp", timestamp).put("kind", kind)
            .put("source", label(source)).put("device", JSONObject()
                .put("manufacturer", label(Build.MANUFACTURER)).put("model", label(Build.MODEL))
                .put("android", label(Build.VERSION.RELEASE)).put("sdk", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"))
            .put("exceptions", exceptions(cause)).put("details", details).put("breadcrumbs", actions)

    private fun enqueue(report: JSONObject) {
        if (!collectionEnabled) return
        if (report.toString().toByteArray(Charsets.UTF_8).size > 90 * 1024) {
            report.put("details", JSONObject().put("truncated", true))
        }
        locked {
            if (receipts().has(report.getString("id"))) return@locked
            val target = File(queue, report.getString("id") + ".json")
            if (target.exists()) return@locked
            queue.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }
                ?.let { files -> files.take((files.size - 127).coerceAtLeast(0)).forEach { it.delete() } }
            val temporary = File(queue, target.name + ".tmp")
            temporary.outputStream().use { stream ->
                stream.write(report.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(target))
        }
    }

    private fun receipts(): JSONObject = runCatching { JSONObject(File(root, "receipts.json").readText()) }.getOrDefault(JSONObject())

    private fun acknowledge(file: File) = locked {
        val sent = receipts().put(file.nameWithoutExtension, System.currentTimeMillis())
        val keys = sent.keys().asSequence().sortedBy { sent.optLong(it) }.toList()
        keys.take((keys.size - 512).coerceAtLeast(0)).forEach { sent.remove(it) }
        val temporary = File(root, "receipts.tmp")
        temporary.outputStream().use { stream -> stream.write(sent.toString().toByteArray()); stream.fd.sync() }
        check(temporary.renameTo(File(root, "receipts.json")))
        file.delete()
    }

    fun error(source: String, throwable: Throwable?) {
        if (!collectionEnabled || throwable is java.util.concurrent.CancellationException) return
        val timestamp = System.currentTimeMillis()
        val actions = recentBreadcrumbs()
        background {
            // Stack traversal, JSON construction and installation lookup belong on
            // IO, even when an Android Components warning originates on the UI thread.
            val key = label(source) + (throwable?.javaClass?.name ?: "") + (throwable?.stackTrace?.firstOrNull()?.toString() ?: "")
            val now = SystemClock.uptimeMillis()
            if (now - (signatures[key] ?: -60000L) >= 60000) {
                if (signatures.size >= 128) signatures.remove(signatures.keys.first())
                signatures[key] = now
                enqueue(report(if (source.startsWith("player_")) "player_error" else "handled_error", source, throwable,
                    timestamp = timestamp, actions = actions))
            }
        }
    }

    override fun log(priority: Log.Priority, tag: String?, throwable: Throwable?, message: String) {
        if (priority == Log.Priority.ERROR || (priority == Log.Priority.WARN && throwable != null)) {
            runCatching { error(tag ?: "android_components", throwable) }
        }
    }

    fun recordCrash(crash: Crash): Boolean {
        if (!collectionEnabled) return false
        return runCatching {
            val details = JSONObject()
            if (crash is Crash.NativeCodeCrash) {
                details.put("process_type", label(crash.processType ?: "unknown"))
                    .put("fatal", crash.isFatalCrash).put("has_minidump", crash.minidumpPath != null)
                // Extra files can contain URLs and memory dumps. Only retain numeric diagnostic metadata.
                crash.extrasPath?.let { path ->
                    val file = File(path)
                    if (file.length() <= 1024 * 1024) runCatching {
                        val extra = JSONObject(file.readText())
                        val safe = JSONObject()
                        for (key in listOf("StartupTime", "CrashTime", "UptimeTS", "AvailablePhysicalMemory", "TotalPhysicalMemory", "OOMAllocationSize", "SystemMemoryUsePercentage")) {
                            extra.optString(key).takeIf { it.matches(Regex("[0-9.]{1,32}")) }?.let { safe.put(key, it) }
                        }
                        details.put("native_metadata", safe)
                        val stacks = extra.optJSONObject("StackTraces")
                            ?: runCatching { JSONObject(extra.optString("StackTraces")) }.getOrNull()
                        stacks?.let {
                            val info = it.optJSONObject("crash_info")
                            details.put("native_signal", label(info?.optString("type") ?: "unknown"))
                            val crashingThread = info?.optInt("crashing_thread", 0) ?: 0
                            val frames = it.optJSONArray("threads")?.optJSONObject(crashingThread)?.optJSONArray("frames")
                            val safeFrames = JSONArray()
                            for (index in 0 until minOf(frames?.length() ?: 0, 64)) {
                                val value = frames?.optJSONObject(index) ?: continue
                                safeFrames.put(JSONObject().put("module_index", value.optInt("module_index", -1))
                                    .put("ip", value.optString("ip").takeIf { ip -> ip.matches(Regex("(?:0x)?[0-9a-fA-F]{1,18}")) } ?: ""))
                            }
                            val modules = it.optJSONArray("modules")
                            val safeModules = JSONArray()
                            for (index in 0 until minOf(modules?.length() ?: 0, 64)) {
                                val value = modules?.optJSONObject(index) ?: continue
                                safeModules.put(JSONObject().put("filename", label(value.optString("filename").substringAfterLast('/')))
                                    .put("debug_id", label(value.optString("debug_id"))))
                            }
                            details.put("native_frames", safeFrames).put("native_modules", safeModules)
                        }
                    }
                }
            }
            val value = report(if (crash is Crash.NativeCodeCrash) "native_crash" else "java_crash", "crash_reporter",
                crash.javaThrowable, details, crash.uuid, crash.timestamp)
            if (crash.versionName != "N/A") value.put("build", label(crash.versionName))
            details.put("recorded_by", BuildConfig.VERSION_NAME)
            enqueue(value)
            if (crash.isFatalCrash) fatalRecorded.set(true)
            true
        }.getOrDefault(false)
    }

    private fun recordEarly(throwable: Throwable) {
        if (fatalRecorded.compareAndSet(false, true)) runCatching { enqueue(report("java_crash", "early_startup", throwable)) }
    }

    fun start(application: Application, crashReporter: CrashReporter) {
        if (!collectionEnabled || !started.compareAndSet(false, true)) return
        Log.addSink(this)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                breadcrumb("activity_created:" + activity.javaClass.simpleName)
                if (activity is FragmentActivity) activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                    object : FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentPreAttached(fm: FragmentManager, fragment: Fragment, context: Context) {
                            breadcrumb("fragment_open:" + fragment.javaClass.simpleName)
                        }
                    }, true)
            }
            override fun onActivityStarted(activity: Activity) { foreground++; heartbeat.set(SystemClock.uptimeMillis()) }
            override fun onActivityResumed(activity: Activity) { breadcrumb("activity_resumed:" + activity.javaClass.simpleName) }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) { foreground = (foreground - 1).coerceAtLeast(0) }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        breadcrumb("session_start")
        background {
            enqueue(report("session_start", "application"))
            runCatching { collectExits() }.onFailure { error("exit_info_read", it) }
            runCatching { runBlocking {
                crashReporter.unsentCrashReportsSince(0).take(32).forEach { crashReporter.submitReport(it).join() }
            } }.onFailure { error("crash_recovery", it) }
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork("upgrid-diagnostics-upload",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpgridDiagnosticsWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            }.onFailure { error("upload_scheduler", it) }
            flush()
        }
        io.scheduleWithFixedDelay({ if (foreground > 0) runCatching { flush() } }, 30, 30, TimeUnit.SECONDS)
        val main = Handler(Looper.getMainLooper())
        val pending = AtomicBoolean(false)
        var reported = false
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "UpgridANRWatchdog").apply { isDaemon = true } }
            .scheduleWithFixedDelay({
                if (foreground > 0 && !Debug.isDebuggerConnected()) {
                    if (!pending.getAndSet(true)) main.post {
                        heartbeat.set(SystemClock.uptimeMillis()); pending.set(false)
                    }
                    val delay = SystemClock.uptimeMillis() - heartbeat.get()
                    if (delay >= 8000 && !reported) {
                        reported = true
                        val details = JSONObject().put("blocked_ms", delay)
                            .put("main_frames", JSONArray(Looper.getMainLooper().thread.stackTrace.take(80).map { frame(it) }))
                        background { enqueue(report("anr_watchdog", "main_thread", details = details)); flush() }
                    }
                    if (delay < 2000) reported = false
                } else { heartbeat.set(SystemClock.uptimeMillis()); reported = false }
            }, 2, 2, TimeUnit.SECONDS)
    }

    private fun collectExits() {
        if (Build.VERSION.SDK_INT < 30) return
        val preferences = context.getSharedPreferences("upgrid_diagnostics", Context.MODE_PRIVATE)
        val cutoff = preferences.getLong("exit_cutoff", 0)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 20)
        for (exit in exits.filter { it.timestamp > cutoff }) {
            if (exit.reason !in setOf(2, 3, 4, 5, 6, 9, 12, 13)) continue
            val details = JSONObject().put("reason", exit.reason).put("status", exit.status)
                .put("pss_kb", exit.pss).put("rss_kb", exit.rss).put("importance", exit.importance)
                .put("process", label(exit.processName.substringAfter(context.packageName, "main")))
                .put("observed_after_update", true)
            if (exit.reason == 6) runCatching {
                exit.traceInputStream?.bufferedReader()?.use { reader ->
                    val frames = JSONArray()
                    var lines = 0
                    while (lines++ < 4000 && frames.length() < 80) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.matches(Regex("at [A-Za-z0-9_.$<>]+\\([A-Za-z0-9_.$ :<>-]{1,160}\\)"))) frames.put(trimmed)
                    }
                    details.put("trace_frames", frames)
                }
            }
            val id = UUID.nameUUIDFromBytes("$installation:${exit.timestamp}:${exit.pid}".toByteArray()).toString()
            enqueue(report("process_exit", "android_exit_info", details = details, id = id, timestamp = exit.timestamp))
        }
        preferences.edit().putLong("exit_cutoff", exits.maxOfOrNull { it.timestamp } ?: cutoff).apply()
    }

    fun flush(): Boolean {
        if (!collectionEnabled || !uploading.compareAndSet(false, true)) return true
        try {
            RandomAccessFile(File(root, "upload.lock"), "rw").use { handle ->
                val lock = handle.channel.tryLock() ?: return true
                lock.use {
                    val files = locked { queue.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }?.take(24).orEmpty() }
                    for (file in files) {
                        val raw = locked { file.takeIf { it.isFile }?.readBytes() } ?: continue
                        val connection = URL(BuildConfig.UPGRID_DIAGNOSTICS_ENDPOINT).openConnection() as HttpsURLConnection
                        try {
                            connection.requestMethod = "POST"
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 8000
                            connection.readTimeout = 8000
                            connection.doOutput = true
                            connection.setRequestProperty("Authorization", "Bearer " + BuildConfig.UPGRID_DIAGNOSTICS_TOKEN)
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.setFixedLengthStreamingMode(raw.size)
                            connection.outputStream.use { it.write(raw) }
                            val status = connection.responseCode
                            if (status in setOf(400, 409, 413, 422)) {
                                locked {
                                    val rejected = File(root, "rejected").apply { mkdirs() }
                                    rejected.listFiles()?.sortedBy { it.lastModified() }?.dropLast(15)?.forEach { it.delete() }
                                    file.renameTo(File(rejected, file.name))
                                }
                                continue
                            }
                            if (status !in 200..201) return false
                            val response = connection.inputStream.bufferedReader().use { reader ->
                                val buffer = CharArray(1024)
                                var total = 0
                                while (total < buffer.size) {
                                    val count = reader.read(buffer, total, buffer.size - total)
                                    if (count < 0) break
                                    total += count
                                }
                                String(buffer, 0, total)
                            }
                            if (JSONObject(response).optString("accepted") != file.nameWithoutExtension) return false
                            acknowledge(file)
                        } finally { connection.disconnect() }
                    }
                }
            }
            return locked { queue.listFiles()?.none { it.extension == "json" } ?: true }
        } catch (_: Exception) { return false } finally { uploading.set(false) }
    }

    companion object {
        @Volatile private var instance: UpgridDiagnostics? = null
        private val enabled get() = BuildConfig.UPGRID_DIAGNOSTICS_ENDPOINT.startsWith("https://") && BuildConfig.UPGRID_DIAGNOSTICS_TOKEN.length >= 32
        @JvmStatic fun get(context: Context): UpgridDiagnostics = instance ?: synchronized(this) {
            instance ?: UpgridDiagnostics(context.applicationContext).also { instance = it }
        }
        @JvmStatic fun installEarly(application: Application) {
            if (!enabled) return
            runCatching {
                val diagnostics = get(application)
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    diagnostics.recordEarly(throwable)
                    previous?.uncaughtException(thread, throwable)
                }
            }
        }
        private fun label(value: String) = value.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(120)
        private fun frame(value: StackTraceElement) = "${label(value.className)}.${label(value.methodName)}(${label(value.fileName?.substringAfterLast('/')?.substringBefore('?') ?: "unknown")}:${value.lineNumber})".take(240)
    }
}

class UpgridCrashService(private val diagnostics: UpgridDiagnostics) : CrashReporterService {
    override val id = "upgrid_vps"
    override val name = "Upgrid diagnostic queue"
    override fun createCrashReportUrl(identifier: String): String? = null
    override fun report(crash: Crash.UncaughtExceptionCrash): String? = if (diagnostics.recordCrash(crash)) crash.uuid else null
    override fun report(crash: Crash.NativeCodeCrash): String? = if (diagnostics.recordCrash(crash)) crash.uuid else null
    override fun report(throwable: Throwable, breadcrumbs: ArrayList<Breadcrumb>): String? { diagnostics.error("caught_exception", throwable); return null }
}

class UpgridDiagnosticsWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = if (UpgridDiagnostics.get(applicationContext).flush()) Result.success() else Result.retry()
}
