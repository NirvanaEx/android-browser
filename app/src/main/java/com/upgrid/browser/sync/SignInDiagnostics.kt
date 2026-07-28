package com.upgrid.browser.sync

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.google.android.gms.common.api.ApiException
import com.upgrid.browser.R
import java.security.MessageDigest

/**
 * Turns a Google sign-in failure into something that can be acted on.
 *
 * Play services reports failures as bare integers, and the app used to answer
 * all of them with one toast — "Couldn't connect the account" — which is
 * indistinguishable from a bug in this app. Almost always it isn't: it's
 * [GoogleAccounts.DEVELOPER_ERROR], meaning no OAuth client in Cloud Console
 * matches this build. That is a five-minute setup step, but only if you know
 * the two values to register, so [signingSha1] digs them out of the installed
 * package rather than leaving them to be looked up on a laptop.
 */
object SignInDiagnostics {

    /** Play services' code for "the user backed out of the account picker". */
    const val SIGN_IN_CANCELLED = 12501

    fun statusCode(error: Throwable): Int? = (error as? ApiException)?.statusCode

    /** True when the failure is the missing-OAuth-client setup step. */
    fun isNotConfigured(error: Throwable): Boolean =
        statusCode(error) == GoogleAccounts.DEVELOPER_ERROR

    /**
     * One line for a toast or a status row. Includes the numeric code for
     * anything we don't have specific words for — an opaque number that can be
     * reported beats a friendly sentence that can't.
     */
    fun describe(context: Context, error: Throwable): String =
        when (val code = statusCode(error)) {
            GoogleAccounts.DEVELOPER_ERROR ->
                context.getString(R.string.settings_account_not_configured)
            SIGN_IN_CANCELLED -> context.getString(R.string.settings_account_cancelled)
            // Not an ApiException at all — a network or plumbing failure. Use
            // its message if it has one; "failed: " with nothing after the
            // colon is worse than the generic line.
            null -> error.message?.take(120)?.takeIf(String::isNotBlank)
                ?.let { context.getString(R.string.settings_sync_failed, it) }
                ?: context.getString(R.string.settings_account_failed)
            else -> context.getString(R.string.settings_account_failed_code, code)
        }

    /** Application id + certificate fingerprint, formatted for Cloud Console. */
    fun oauthClientDetails(context: Context): String = context.getString(
        R.string.account_setup_details,
        context.packageName,
        signingSha1(context).ifBlank { "—" },
    )

    /**
     * SHA-1 of the certificate this APK is signed with, colon-separated upper
     * hex — the exact format Cloud Console's Android OAuth client asks for.
     *
     * Read from the installed package rather than from a build constant, so it
     * stays right if the signing key ever changes.
     */
    fun signingSha1(context: Context): String = try {
        val signature = firstSignature(context)
        signature?.let {
            MessageDigest.getInstance("SHA-1")
                .digest(it.toByteArray())
                .joinToString(":") { byte -> "%02X".format(byte) }
        }.orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun firstSignature(context: Context): Signature? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
                ?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
                ?.firstOrNull()
        }
    }
}
