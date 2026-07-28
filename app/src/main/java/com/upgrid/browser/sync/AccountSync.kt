package com.upgrid.browser.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.upgrid.browser.bookmarks.BookmarkStore
import com.upgrid.browser.history.HistoryStore
import com.upgrid.browser.prefs.BrowserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google sign-in, scoped to the one thing this browser wants from an account:
 * a private corner of Drive to keep bookmarks and history in.
 *
 * We ask for `drive.appdata` and nothing else. That scope cannot see, list or
 * touch any file the user didn't create through this app — so "connect a Google
 * account" here does not mean handing the browser the user's Drive.
 *
 * Sign-in is matched by (application id, signing certificate SHA-1) against an
 * OAuth client in Cloud Console; there is no client id in the source. That's
 * also why the debug signing key is checked into the repo — see the
 * `signingConfigs` comment in `app/build.gradle.kts`.
 */
object GoogleAccounts {

    /** Hidden per-app Drive storage. The only scope we ever request. */
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

    /**
     * Play services' code for "no OAuth client matches this app". By far the
     * most likely failure on a fresh checkout, and utterly opaque as a bare
     * number — the settings sheet turns it into setup instructions.
     */
    const val DEVELOPER_ERROR = 10

    private fun options(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_APPDATA))
            .build()

    fun client(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(context.applicationContext, options())

    fun signInIntent(context: Context): Intent = client(context).signInIntent

    /**
     * The signed-in account, but only once it actually carries the Drive scope.
     *
     * Play services will happily hand back an account that signed in before the
     * consent screen was completed. Treating that as connected produces a
     * settings row that says "signed in as …" next to a sync button that fails
     * every time, so a half-granted account reads as no account here.
     */
    fun current(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)
            ?.takeIf { GoogleSignIn.hasPermissions(it, Scope(DRIVE_APPDATA)) }

    /** Result of the sign-in activity. Throws nothing; failure comes back typed. */
    fun accountFromResult(data: Intent?): Result<GoogleSignInAccount> = runCatching {
        GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
    }

    fun signOut(context: Context, onDone: () -> Unit) {
        client(context).signOut().addOnCompleteListener { onDone() }
    }
}

/** What a sync attempt did. Everything the UI needs to render, no exceptions. */
sealed interface SyncOutcome {
    /** Merged and uploaded. Counts are the totals held locally afterwards. */
    data class Success(val bookmarks: Int, val pages: Int) : SyncOutcome

    /** No account connected yet. */
    data object NotSignedIn : SyncOutcome

    /**
     * Google wants the user to approve something (usually the Drive scope on
     * first run). [intent] is the consent screen; launch it and sync again.
     */
    data class NeedsConsent(val intent: Intent) : SyncOutcome

    /** Network down, Drive unhappy, token refused. [reason] is user-facing. */
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Two-way sync of bookmarks + history through the Drive app-data folder.
 *
 * One document, read-merge-write, no server, no conflict UI:
 *
 *  1. download the remote document (absent on first ever sync),
 *  2. merge it *into* the local tables — union by URL, never subtraction,
 *  3. upload the merged local state back.
 *
 * Merging in both directions and keeping the union is what lets two phones
 * converge without a coordinator. It also means a deletion on one device does
 * not propagate: without a tombstone log, "this URL is missing" and "this URL
 * was deleted" are the same bytes, and guessing wrong loses the user's data.
 * Erring toward keeping things is the recoverable direction to be wrong in.
 */
class SyncEngine(
    private val context: Context,
    private val bookmarks: BookmarkStore = BookmarkStore(context),
    private val history: HistoryStore = HistoryStore(context),
    private val preferences: BrowserPreferences = BrowserPreferences(context),
) {

    suspend fun sync(): SyncOutcome {
        val account = GoogleAccounts.current(context) ?: return SyncOutcome.NotSignedIn
        val androidAccount = account.account ?: return SyncOutcome.NotSignedIn

        val token = when (val first = fetchToken(androidAccount)) {
            is TokenResult.Ok -> first.token
            is TokenResult.Recoverable -> return SyncOutcome.NeedsConsent(first.intent)
            is TokenResult.Error -> return SyncOutcome.Failed(first.reason)
        }

        try {
            return runSync(token)
        } catch (e: DriveException) {
            if (e.code != HTTP_UNAUTHORIZED) return SyncOutcome.Failed(e.describe())
            // Fall through: 401 is a stale cached token, which is recoverable.
        } catch (e: Exception) {
            return SyncOutcome.Failed(e.describe())
        }

        // getToken keeps handing back the same dead string until it is
        // explicitly cleared, so without this the account stays broken until
        // the app is reinstalled. One retry only — a second 401 is a real
        // authorization problem, not a stale cache.
        runCatching { withContext(Dispatchers.IO) { GoogleAuthUtil.clearToken(context, token) } }

        val fresh = when (val second = fetchToken(androidAccount)) {
            is TokenResult.Ok -> second.token
            is TokenResult.Recoverable -> return SyncOutcome.NeedsConsent(second.intent)
            is TokenResult.Error -> return SyncOutcome.Failed(second.reason)
        }
        return try {
            runSync(fresh)
        } catch (e: Exception) {
            SyncOutcome.Failed(e.describe())
        }
    }

    private suspend fun fetchToken(account: Account): TokenResult = try {
        TokenResult.Ok(
            withContext(Dispatchers.IO) {
                GoogleAuthUtil.getToken(
                    context,
                    account,
                    "oauth2:${GoogleAccounts.DRIVE_APPDATA}",
                )
            }
        )
    } catch (e: UserRecoverableAuthException) {
        // Almost always the Drive consent screen on first sync. It has to be
        // launched from an Activity, so it travels back to the caller rather
        // than being handled here.
        e.intent?.let { TokenResult.Recoverable(it) } ?: TokenResult.Error(e.describe())
    } catch (e: Exception) {
        TokenResult.Error(e.describe())
    }

    private sealed interface TokenResult {
        data class Ok(val token: String) : TokenResult
        data class Recoverable(val intent: Intent) : TokenResult
        data class Error(val reason: String) : TokenResult
    }

    private suspend fun runSync(token: String): SyncOutcome {
        val drive = DriveAppData(token)

        val fileId = drive.findFile(SyncPayload.FILE_NAME)
        if (fileId != null) {
            SyncPayload.parse(drive.download(fileId))?.let { remote ->
                bookmarks.mergeIn(remote.bookmarks)
                history.mergeIn(remote.history)
            }
        }

        // Read back *after* merging so the upload carries the union rather than
        // the local half of it — otherwise each device would keep overwriting
        // the other's additions and neither would ever converge.
        val mergedBookmarks = bookmarks.all()
        val mergedHistory = history.entries(limit = SyncPayload.HISTORY_LIMIT)
        val now = System.currentTimeMillis()

        drive.upload(
            fileId = fileId,
            name = SyncPayload.FILE_NAME,
            content = SyncPayload(now, mergedBookmarks, mergedHistory).toJson(),
        )

        preferences.lastSyncAt = now
        return SyncOutcome.Success(mergedBookmarks.size, mergedHistory.size)
    }

    /** Exception → one short line a user can act on. Never leaks the token. */
    private fun Throwable.describe(): String =
        message?.take(160)?.ifBlank { null } ?: this::class.java.simpleName

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
