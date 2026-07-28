package com.upgrid.browser.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Non-2xx from Drive. [code] is the HTTP status; 401 means "token is stale". */
class DriveException(val code: Int, message: String) : IOException(message)

/**
 * The four Drive v3 calls we need, over the app-data folder.
 *
 * `appDataFolder` is a per-application hidden space in the user's Drive: it
 * doesn't show up in the Drive UI, doesn't eat into a visible folder, and is
 * scoped to `drive.appdata` — an app holding that scope can't read a single one
 * of the user's real files. For "sync my bookmarks between my own phones" it is
 * exactly the right amount of access to ask for, which is why there is no
 * server in this design at all.
 *
 * Written against [HttpURLConnection] rather than a Drive client library on
 * purpose — see the dependency comment in `app/build.gradle.kts`.
 *
 * Instances are cheap and hold one access token; make a new one per sync.
 */
class DriveAppData(private val accessToken: String) {

    /** Id of [name] inside the app-data folder, or null if it isn't there yet. */
    suspend fun findFile(name: String): String? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("name = '$name'", "UTF-8")
        val body = request(
            url = "$API/files?spaces=appDataFolder&q=$query&fields=files(id)&pageSize=1",
            method = "GET",
        )
        JSONObject(body).optJSONArray("files")?.optJSONObject(0)?.optString("id")
            ?.takeIf { it.isNotEmpty() }
    }

    /** Raw contents of a file by id. */
    suspend fun download(fileId: String): String = withContext(Dispatchers.IO) {
        request(url = "$API/files/$fileId?alt=media", method = "GET")
    }

    /**
     * Write [content] to [name], creating the file on first sync.
     *
     * Create needs metadata (the name, and `parents: [appDataFolder]` — without
     * it the file lands in the user's visible Drive root), so it goes as a
     * multipart/related upload. Update only replaces bytes, so it's a plain
     * media body.
     *
     * Returns the file id, which the caller may cache for the next round.
     */
    suspend fun upload(fileId: String?, name: String, content: String): String =
        withContext(Dispatchers.IO) {
            if (fileId == null) create(name, content) else update(fileId, content)
        }

    // --- Internals ---------------------------------------------------------

    private fun create(name: String, content: String): String {
        val metadata = JSONObject().apply {
            put("name", name)
            put("parents", org.json.JSONArray().put("appDataFolder"))
        }.toString()

        val body = buildString {
            append("--").append(BOUNDARY).append(CRLF)
            append("Content-Type: application/json; charset=UTF-8").append(CRLF).append(CRLF)
            append(metadata).append(CRLF)
            append("--").append(BOUNDARY).append(CRLF)
            append("Content-Type: application/json; charset=UTF-8").append(CRLF).append(CRLF)
            append(content).append(CRLF)
            append("--").append(BOUNDARY).append("--").append(CRLF)
        }

        val response = request(
            url = "$UPLOAD/files?uploadType=multipart&fields=id",
            method = "POST",
            contentType = "multipart/related; boundary=$BOUNDARY",
            payload = body,
        )
        return JSONObject(response).optString("id")
    }

    private fun update(fileId: String, content: String): String {
        // HttpURLConnection rejects PATCH outright ("Invalid HTTP method"), and
        // the JDK offers no way in. Google's APIs honour the override header,
        // which is the documented escape hatch for exactly this client.
        val response = request(
            url = "$UPLOAD/files/$fileId?uploadType=media&fields=id",
            method = "POST",
            contentType = "application/json; charset=UTF-8",
            payload = content,
            overrideMethod = "PATCH",
        )
        return JSONObject(response).optString("id").ifEmpty { fileId }
    }

    private fun request(
        url: String,
        method: String,
        contentType: String? = null,
        payload: String? = null,
        overrideMethod: String? = null,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $accessToken")
            overrideMethod?.let { setRequestProperty("X-HTTP-Method-Override", it) }
            contentType?.let { setRequestProperty("Content-Type", it) }
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doInput = true
            if (payload != null) doOutput = true
        }

        try {
            payload?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }

            val code = connection.responseCode
            if (code !in 200..299) {
                // Read the error stream, not the input stream — the latter
                // throws on a failed response and we'd lose Drive's actual
                // complaint ("insufficient permissions", "quota exceeded", …),
                // which is the only thing that makes these debuggable.
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw DriveException(code, "Drive HTTP $code: ${detail.orEmpty().take(300)}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

        const val CRLF = "\r\n"
        const val BOUNDARY = "upgrid-sync-boundary"
        const val TIMEOUT_MS = 30_000
    }
}
