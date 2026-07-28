package com.upgrid.browser.account

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What the account server sends back: who you are, and what you get.
 */
data class Provision(
    val account: Account,
    /** `wg-quick` text, or null when this account has no VPN. */
    val vpnConfig: String?,
)

/**
 * Sign-in against the account server.
 *
 * Deliberately tiny: one GET, HTTP basic auth, a JSON document back. The server
 * side is a static file behind `auth_basic` in nginx — there is no session, no
 * token to refresh and no endpoint to keep compatible, because the only
 * question being asked is "here are my credentials, what is my profile?".
 *
 * ```json
 * {
 *   "user": { "login": "superadmin", "name": "Superadmin", "role": "superadmin" },
 *   "vpn":  { "config": "[Interface]\nPrivateKey = …" }
 * }
 * ```
 *
 * Failure is graded, because the three cases mean different things to the
 * caller: wrong password is final, an unreachable server is not (the app falls
 * back to the local password check and whatever profile it already has).
 */
class AccountApi(private val client: Client) {

    sealed interface Outcome {
        data class Success(val provision: Provision) : Outcome
        data object BadCredentials : Outcome
        data class Unreachable(val reason: String) : Outcome
    }

    suspend fun signIn(baseUrl: String, login: String, password: String): Outcome =
        withContext(Dispatchers.IO) {
            val url = profileUrl(baseUrl, login)
            val credentials = Base64.encodeToString(
                "$login:$password".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            val request = Request(
                url = url,
                method = Request.Method.GET,
                headers = MutableHeaders().apply {
                    set("Authorization", "Basic $credentials")
                    set("Accept", "application/json")
                },
                connectTimeout = TIMEOUT,
                readTimeout = TIMEOUT,
            )

            try {
                client.fetch(request).use { response ->
                    when {
                        response.status == 401 || response.status == 403 ->
                            Outcome.BadCredentials
                        response.status !in SUCCESS ->
                            Outcome.Unreachable("HTTP ${response.status}")
                        else -> parse(response.body.string(), login)
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Account server unreachable", t)
                Outcome.Unreachable(t.message ?: t.javaClass.simpleName)
            }
        }

    private fun parse(body: String, login: String): Outcome = runCatching {
        val json = JSONObject(body)
        val user = json.optJSONObject("user")
        val config = json.optJSONObject("vpn")?.optString("config")?.takeIf { it.isNotBlank() }
        Outcome.Success(
            Provision(
                account = Account(
                    login = user?.optString("login")?.ifBlank { login } ?: login,
                    name = user?.optString("name")?.ifBlank { login } ?: login,
                    role = user?.optString("role", "user") ?: "user",
                ),
                vpnConfig = config,
            ),
        )
    }.getOrElse { Outcome.Unreachable("bad response") }

    private fun profileUrl(baseUrl: String, login: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return "$base/${login.trim().lowercase()}.json"
    }

    private companion object {
        const val TAG = "AccountApi"
        val SUCCESS = 200..299

        /** Sign-in is in front of a waiting user; a slow answer is a failed one. */
        val TIMEOUT = 8L to TimeUnit.SECONDS
    }
}
