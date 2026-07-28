package com.upgrid.browser.download

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One file the browser has fetched, or is fetching. */
data class DownloadRecord(
    val id: String,
    val fileName: String,
    /** Page or link the file came from. Shown as the second line. */
    val url: String,
    val mimeType: String,
    /** Size the server promised, or 0 when it didn't say. */
    val totalBytes: Long,
    /** Bytes written so far. */
    val bytes: Long,
    val status: Status,
    val createdAt: Long,
    /** `content://` URI for MediaStore entries; empty on the legacy path. */
    val uri: String,
    /** Absolute path for the legacy path; empty for MediaStore entries. */
    val path: String,
) {
    enum class Status { RUNNING, DONE, FAILED }

    val isFinished: Boolean get() = status != Status.RUNNING

    /** 0..1, or null when the server never said how big the file is. */
    val progress: Float?
        get() = if (totalBytes <= 0) null else (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * The download list, persisted.
 *
 * SharedPreferences holding one JSON array rather than a table: a download list
 * is a few dozen rows that are only ever read all at once, and a third
 * SQLiteOpenHelper for it would be a connection and a schema to migrate for
 * nothing.
 *
 * Exposed as a [StateFlow] because the downloads screen has to watch progress
 * on a file that is being written while it is open. Progress ticks update the
 * flow but are NOT written to disk — persisting twenty times a second to
 * describe a number that is meaningless after a restart would be the one
 * expensive thing this class does.
 */
class DownloadRecords(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(load())

    /** Newest first. */
    val records: StateFlow<List<DownloadRecord>> = state.asStateFlow()

    fun add(record: DownloadRecord) {
        state.value = (listOf(record) + state.value).take(MAX_ROWS)
        persist()
    }

    /** Replace a row by id. [durable] false = progress tick, don't touch disk. */
    fun update(record: DownloadRecord, durable: Boolean = true) {
        val updated = state.value.map { if (it.id == record.id) record else it }
        // A record can vanish while its download runs (the user cleared the
        // list); re-adding it here would resurrect a row they deleted.
        if (updated.none { it.id == record.id }) return
        state.value = updated
        if (durable) persist()
    }

    fun byId(id: String): DownloadRecord? = state.value.firstOrNull { it.id == id }

    fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
        persist()
    }

    fun clear() {
        state.value = emptyList()
        persist()
    }

    /**
     * Rows left mid-flight by a process death. Nothing is resuming them, so a
     * spinner that never moves would be a lie.
     */
    fun failInterrupted() {
        val fixed = state.value.map {
            if (it.status == DownloadRecord.Status.RUNNING) {
                it.copy(status = DownloadRecord.Status.FAILED)
            } else {
                it
            }
        }
        if (fixed != state.value) {
            state.value = fixed
            persist()
        }
    }

    // --- Storage -----------------------------------------------------------

    private fun persist() {
        val array = JSONArray()
        state.value.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("id", record.id)
                    put("name", record.fileName)
                    put("url", record.url)
                    put("mime", record.mimeType)
                    put("total", record.totalBytes)
                    put("bytes", record.bytes)
                    put("status", record.status.name)
                    put("at", record.createdAt)
                    put("uri", record.uri)
                    put("path", record.path)
                },
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<DownloadRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                DownloadRecord(
                    id = o.optString("id"),
                    fileName = o.optString("name"),
                    url = o.optString("url"),
                    mimeType = o.optString("mime"),
                    totalBytes = o.optLong("total"),
                    bytes = o.optLong("bytes"),
                    status = runCatching {
                        DownloadRecord.Status.valueOf(o.optString("status"))
                    }.getOrDefault(DownloadRecord.Status.FAILED),
                    createdAt = o.optLong("at"),
                    uri = o.optString("uri"),
                    path = o.optString("path"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val FILE = "upgrid_downloads"
        const val KEY = "items"

        /** A download list is a recent-activity list, not an archive. */
        const val MAX_ROWS = 200
    }
}
