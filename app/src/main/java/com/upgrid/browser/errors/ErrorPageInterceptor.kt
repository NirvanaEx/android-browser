package com.upgrid.browser.errors

import android.content.Context
import androidx.annotation.StringRes
import com.upgrid.browser.R
import mozilla.components.browser.errorpages.ErrorType
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor
import java.net.URLEncoder

/**
 * What the browser shows when a page didn't arrive.
 *
 * Without this the engine hands back its own bare `about:neterror` — an
 * unstyled wall of Firefox text in whatever language Gecko was built with,
 * naming things like "PR_CONNECT_RESET_ERROR". A person who typed an address
 * and got nothing needs three sentences: what happened, whether it is their
 * fault, and a button.
 *
 * Written here rather than taken from `browser-errorpages`, whose
 * `ErrorPages.createUrlEncodedErrorPage` would do this in one line. That page
 * is Firefox's: Firefox's wording, Firefox's layout, Firefox's product name in
 * the middle of it. Nine tenths of the work in this file is the mapping below,
 * which is the part that has to be ours anyway — and having it here means the
 * text lives in strings.xml next to every other sentence in the app.
 *
 * The engine calls this from `NavigationDelegate.onLoadError` and loads
 * whatever URI comes back **in place of** the failed page, which is what keeps
 * the address bar showing the address you asked for rather than the error's.
 *
 * That last part has a consequence for the page at the other end, and it is
 * why the first version of this shipped blank: the failed address stays in
 * `location`, so the page cannot read these parameters off `location.search` —
 * they are on `document.documentURI`. The other half of that bug: a document
 * loaded from `resource://` is system-privileged, and Gecko's CSP for those
 * has no `'unsafe-inline'`, so the stylesheet and the script have to be
 * separate files. Both notes live in `assets/error.html` too.
 */
class ErrorPageInterceptor(private val context: Context) : RequestInterceptor {

    /** One row of the table: a title and a sentence under it. */
    private class Page(@StringRes val title: Int, @StringRes val body: Int)

    override fun onErrorRequest(
        session: EngineSession,
        errorType: ErrorType,
        uri: String?,
    ): RequestInterceptor.ErrorResponse {
        val page = pageFor(errorType)
        val address = uri.orEmpty()

        val query = buildString {
            append("resource://android/assets/error.html?")
            append("t=").append(context.getString(page.title).enc())
            append("&m=").append(context.getString(page.body).enc())
            append("&u=").append(address.enc())
            append("&r=").append(context.getString(R.string.error_retry).enc())
            append("&b=").append(context.getString(R.string.error_back).enc())
        }
        return RequestInterceptor.ErrorResponse(query)
    }

    /**
     * Thirty engine error codes, twelve things worth telling somebody.
     *
     * The grouping is by *what to do about it*, not by what went wrong in the
     * network stack: "the connection was reset" and "the connection timed out"
     * are the same sentence to everyone who isn't debugging one. Where the
     * distinction changes the answer — no internet at all, versus this one site
     * being down, versus the address not existing — it gets its own row,
     * because those three lead to three different next actions.
     */
    @Suppress("ComplexMethod")
    private fun pageFor(errorType: ErrorType): Page = when (errorType) {
        ErrorType.ERROR_NO_INTERNET,
        ErrorType.ERROR_OFFLINE,
        -> Page(R.string.error_offline_title, R.string.error_offline_body)

        ErrorType.ERROR_NET_TIMEOUT,
        ErrorType.ERROR_NET_INTERRUPT,
        ErrorType.ERROR_NET_RESET,
        -> Page(R.string.error_timeout_title, R.string.error_timeout_body)

        ErrorType.ERROR_CONNECTION_REFUSED,
        ErrorType.ERROR_PROXY_CONNECTION_REFUSED,
        -> Page(R.string.error_refused_title, R.string.error_refused_body)

        ErrorType.ERROR_UNKNOWN_HOST,
        ErrorType.ERROR_UNKNOWN_PROXY_HOST,
        -> Page(R.string.error_host_title, R.string.error_host_body)

        ErrorType.ERROR_SECURITY_SSL,
        ErrorType.ERROR_SECURITY_BAD_CERT,
        ErrorType.ERROR_BAD_HSTS_CERT,
        -> Page(R.string.error_ssl_title, R.string.error_ssl_body)

        ErrorType.ERROR_SAFEBROWSING_MALWARE_URI,
        ErrorType.ERROR_SAFEBROWSING_HARMFUL_URI,
        ErrorType.ERROR_SAFEBROWSING_PHISHING_URI,
        ErrorType.ERROR_SAFEBROWSING_UNWANTED_URI,
        ErrorType.ERROR_HARMFULADDON_URI,
        -> Page(R.string.error_blocked_title, R.string.error_blocked_body)

        ErrorType.ERROR_MALFORMED_URI,
        ErrorType.ERROR_UNKNOWN_PROTOCOL,
        ErrorType.ERROR_PORT_BLOCKED,
        ErrorType.ERROR_UNKNOWN_SOCKET_TYPE,
        -> Page(R.string.error_address_title, R.string.error_address_body)

        ErrorType.ERROR_REDIRECT_LOOP ->
            Page(R.string.error_redirect_title, R.string.error_redirect_body)

        ErrorType.ERROR_CORRUPTED_CONTENT,
        ErrorType.ERROR_INVALID_CONTENT_ENCODING,
        ErrorType.ERROR_UNSAFE_CONTENT_TYPE,
        -> Page(R.string.error_corrupt_title, R.string.error_corrupt_body)

        ErrorType.ERROR_CONTENT_CRASHED ->
            Page(R.string.error_crashed_title, R.string.error_crashed_body)

        ErrorType.ERROR_FILE_NOT_FOUND,
        ErrorType.ERROR_FILE_ACCESS_DENIED,
        -> Page(R.string.error_file_title, R.string.error_file_body)

        ErrorType.ERROR_HTTPS_ONLY ->
            Page(R.string.error_https_title, R.string.error_https_body)

        ErrorType.UNKNOWN ->
            Page(R.string.error_unknown_title, R.string.error_unknown_body)
    }

    /**
     * `URLEncoder` is form encoding, not URL encoding: it writes a space as `+`,
     * which inside a query value reads back as a literal plus. Every sentence
     * on this page has spaces in it.
     */
    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
