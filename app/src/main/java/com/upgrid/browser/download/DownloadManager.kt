package com.upgrid.browser.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.lib.state.ext.flow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

/**
 * Downloads, done in the app.
 *
 * The engine hands us a fully-formed response the moment it decides it can't
 * render something — GeckoView has already made the request, with the page's
 * cookies and referrer, and the body is sitting there open. So the work here is
 * to copy that stream somewhere the user can find it and to remember that we
 * did. `feature-downloads` would do the same thing through a foreground
 * service, three prompt dialogs and a notification channel; none of that is
 * needed to save a file, and all of it is UI we'd then have to restyle.
 *
 * Where the bytes go: the public Downloads folder through MediaStore on
 * Android 10+, which needs no permission and puts the file in the system's own
 * download list. Below that MediaStore has no Downloads collection and writing
 * to shared storage needs WRITE_EXTERNAL_STORAGE, so the file lands in the
 * app's own external directory instead — visible through our list and openable
 * through the FileProvider, without a permission dialog on top of a download
 * the user already asked for.
 *
 * Lives for the process, not for an Activity: a download must survive the
 * screen being rotated or the app being backgrounded.
 */
class DownloadManager(
    private val context: Context,
    private val store: BrowserStore,
    private val client: Client,
    val records: DownloadRecords,
) {

    /** What the UI is told, on the main thread. */
    sealed interface Event {
        data class Started(val fileName: String) : Event
        data class Finished(val record: DownloadRecord) : Event
        data class Failed(val fileName: String) : Event
    }

    var onEvent: ((Event) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Download ids already picked up. The store keeps `content.download` set
     * until it is consumed, and it is consumed only when the copy finishes —
     * so without this every state tick in between would start the file again.
     */
    private val started = Collections.synchronizedSet(mutableSetOf<String>())

    /** Begin watching the store. Call once, from the Application. */
    fun start() {
        records.failInterrupted()
        scope.launch {
            store.flow().collect { state ->
                state.tabs.forEach { tab ->
                    val download = tab.content.download ?: return@forEach
                    if (started.add(download.id)) {
                        launch { perform(tab.id, download) }
                    }
                }
            }
        }
    }

    private suspend fun perform(tabId: String, download: DownloadState) {
        val name = download.fileName?.takeIf { it.isNotBlank() }
            ?: FileNames.guessFileName(null, download.url, download.contentType)

        var record = DownloadRecord(
            id = download.id,
            fileName = name,
            url = download.url,
            mimeType = download.contentType.orEmpty(),
            totalBytes = download.contentLength ?: 0L,
            bytes = 0L,
            status = DownloadRecord.Status.RUNNING,
            createdAt = System.currentTimeMillis(),
            uri = "",
            path = "",
        )
        records.add(record)
        post(Event.Started(name))

        var sink: Sink? = null
        try {
            // A local val as well as the field: the field is what the catch and
            // finally blocks clean up, and a nullable var can't be smart-cast
            // inside the lambdas below.
            val open = openSink(name, record.mimeType)
            sink = open
            // The engine's own response when there is one: re-fetching would
            // repeat the request without the session that authorised it, and
            // plenty of files are behind a login.
            val copied = download.response?.body?.useStream { input ->
                copy(input, open.stream, record)
            } ?: fetch(download).use { response ->
                if (response.status !in HTTP_OK) throw IOException("HTTP ${response.status}")
                response.body.useStream { input -> copy(input, open.stream, record) }
            }
            open.stream.flush()
            open.complete()

            record = record.copy(
                status = DownloadRecord.Status.DONE,
                bytes = copied,
                totalBytes = if (record.totalBytes > 0) record.totalBytes else copied,
                uri = open.uri,
                path = open.path,
            )
            records.update(record)
            post(Event.Finished(record))
        } catch (t: Throwable) {
            runCatching { sink?.discard() }
            records.update(record.copy(status = DownloadRecord.Status.FAILED))
            post(Event.Failed(name))
        } finally {
            runCatching { sink?.stream?.close() }
            // Consuming closes the engine's response, so it can only happen
            // once the stream has been read to the end.
            store.dispatch(ContentAction.ConsumeDownloadAction(tabId, download.id))
        }
    }

    private fun fetch(download: DownloadState) = client.fetch(
        Request(
            url = download.url,
            headers = MutableHeaders().apply {
                download.userAgent?.let { set("User-Agent", it) }
                download.referrerUrl?.let { set("Referer", it) }
            },
        ),
    )

    /**
     * Copy, reporting progress a few times a second.
     *
     * Not on every buffer: a fast connection fills a 64 KB buffer hundreds of
     * times a second and each report walks the whole record list.
     */
    private fun copy(input: InputStream, output: OutputStream, record: DownloadRecord): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var lastTick = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
            val now = SystemClock.elapsedRealtime()
            if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                lastTick = now
                records.update(record.copy(bytes = total), durable = false)
            }
        }
        return total
    }

    private fun post(event: Event) {
        main.post { onEvent?.invoke(event) }
    }

    // --- Where the bytes land ----------------------------------------------

    /**
     * An open output plus what it takes to finish or abandon it. A half-written
     * MediaStore row has to be deleted rather than left pending, or it shows up
     * in the system's Downloads list as a zero-byte file forever.
     */
    private class Sink(
        val stream: OutputStream,
        val uri: String,
        val path: String,
        val complete: () -> Unit,
        val discard: () -> Unit,
    )

    private fun openSink(fileName: String, mimeType: String): Sink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreSink(fileName, mimeType)
        } else {
            legacySink(fileName)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreSink(fileName: String, mimeType: String): Sink {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            if (mimeType.isNotBlank()) put(MediaStore.Downloads.MIME_TYPE, mimeType)
            // Pending hides the row from other apps until it's whole.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the entry")
        val stream = resolver.openOutputStream(uri)
            ?: throw IOException("MediaStore entry is not writable")

        return Sink(
            stream = stream,
            uri = uri.toString(),
            path = "",
            complete = {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
            },
            discard = { runCatching { resolver.delete(uri, null, null) } },
        )
    }

    private fun legacySink(fileName: String): Sink {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOWNLOADS)
        directory.mkdirs()

        var file = File(directory, fileName)
        var counter = 1
        while (file.exists() && counter < MAX_NAME_ATTEMPTS) {
            val stem = fileName.substringBeforeLast('.', fileName)
            val suffix = fileName.substringAfterLast('.', "")
            val next = if (suffix.isEmpty()) "$stem ($counter)" else "$stem ($counter).$suffix"
            file = File(directory, next)
            counter++
        }

        return Sink(
            stream = file.outputStream(),
            uri = "",
            path = file.absolutePath,
            complete = {},
            discard = { runCatching { file.delete() } },
        )
    }

    private companion object {
        val HTTP_OK = 200..299
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_NAME_ATTEMPTS = 500
    }
}
