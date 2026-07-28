package com.upgrid.browser.download

import android.net.Uri
import android.webkit.MimeTypeMap
import mozilla.components.concept.engine.DownloadDelegate
import java.net.URLDecoder

/**
 * What to call the file.
 *
 * The engine asks through [DownloadDelegate] the moment a response turns out to
 * be a download, and it asks before anything is written — so this is the only
 * chance to read `Content-Disposition`, which is where servers put the real
 * name and which never reaches [mozilla.components.browser.state.state.content.DownloadState].
 *
 * Written out rather than delegated to a-c's DownloadUtils because the whole
 * job is three fallbacks deep and each one is two lines: the header, the URL's
 * last segment, and a generic name with an extension guessed from the MIME
 * type. Nothing here is worth an abstraction.
 */
object FileNames : DownloadDelegate {

    override fun guessFileName(
        contentDisposition: String?,
        url: String?,
        mimeType: String?,
    ): String {
        val fromHeader = fromDisposition(contentDisposition)
        if (!fromHeader.isNullOrBlank()) return withExtension(sanitize(fromHeader), mimeType)

        val fromUrl = url?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }
        if (!fromUrl.isNullOrBlank()) return withExtension(sanitize(fromUrl), mimeType)

        return withExtension(FALLBACK, mimeType)
    }

    /**
     * `attachment; filename="report.pdf"` and the RFC 5987 form
     * `attachment; filename*=UTF-8''%D0%9E%D1%82%D1%87%D1%91%D1%82.pdf`.
     *
     * The starred form wins when both are present: that's the one that can
     * carry a name that isn't ASCII, and a server sending both puts a
     * transliterated fallback in the plain one.
     */
    private fun fromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null

        STARRED.find(header)?.groupValues?.getOrNull(1)?.let { raw ->
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        PLAIN.find(header)?.groupValues?.getOrNull(1)?.let { raw ->
            if (raw.isNotBlank()) return raw.trim('"', ' ')
        }
        return null
    }

    /** Strip anything that would make this a path rather than a name. */
    private fun sanitize(name: String): String = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(ILLEGAL, "_")
        .trim()
        .take(MAX_LENGTH)
        .ifBlank { FALLBACK }

    /**
     * A name with no extension is a file Android can't open afterwards — it
     * dispatches on the extension as much as on the MIME type.
     */
    private fun withExtension(name: String, mimeType: String?): String {
        if (name.contains('.') && !name.endsWith('.')) return name
        val extension = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (extension.isNullOrBlank()) name else "${name.trimEnd('.')}.$extension"
    }

    private val STARRED = Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val PLAIN = Regex("""filename\s*=\s*("[^"]+"|[^;]+)""", RegexOption.IGNORE_CASE)
    /** Path separators and the punctuation Android's own pickers choke on. */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

    private const val FALLBACK = "file"

    /** Comfortably under every filesystem's limit once a suffix is added. */
    private const val MAX_LENGTH = 120
}
