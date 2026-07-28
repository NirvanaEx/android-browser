package com.upgrid.browser.account

import com.upgrid.browser.BrowserComponents
import com.upgrid.browser.prefs.BrowserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signing in, and what signing in sets up.
 *
 * The account is what makes the VPN a switch rather than a form: whatever the
 * server (or the last successful sign-in) knows about this account is written
 * straight into [com.upgrid.browser.vpn.VpnSettings], so the profile fields are
 * filled in before the user ever opens them. That is the whole feature — the
 * fields still exist, one screen deeper, for when something needs changing by
 * hand.
 */
class AccountController(private val components: BrowserComponents) {

    private val store get() = components.accounts
    private val prefs by lazy { BrowserPreferences(components.context) }
    private val api by lazy { AccountApi(components.httpClient) }

    sealed interface Result {
        /**
         * Signed in — the only success, whichever half carried it.
         *
         * The distinction the user cares about is not "server or device", it is
         * whether the VPN is now set up: [provisioned] is true when a profile is
         * in place, freshly fetched or kept from an earlier sign-in.
         */
        data class Success(val account: Account, val provisioned: Boolean) : Result

        data object WrongCredentials : Result
    }

    val current: Account? get() = store.current

    /**
     * The local half runs on IO: verifying a password is a PBKDF2 derivation,
     * deliberately slow, and this is called from a click handler.
     */
    suspend fun signIn(login: String, password: String): Result = withContext(Dispatchers.IO) {
        val server = prefs.accountServer.trim()
        if (server.isBlank()) return@withContext locally(login, password)

        when (val outcome = api.signIn(server, login, password)) {
            is AccountApi.Outcome.Success -> {
                val provision = outcome.provision
                store.signIn(provision.account, provision.vpnConfig)
                provision.vpnConfig?.let(::applyVpn)
                Result.Success(provision.account, provision.vpnConfig != null)
            }
            // A server that answers 401 is not automatically right about this
            // phone. It answers exactly the same way to a login it has never
            // heard of, to a path that belongs to some other site, and to a
            // half-finished setup — and the result of believing it would be a
            // browser its owner cannot sign in to with the correct password.
            // So a rejection sends us to the device's own copy, which is the
            // thing the owner actually controls. The VPN profile is unaffected:
            // it only ever comes from the server.
            AccountApi.Outcome.BadCredentials -> locally(login, password)
            is AccountApi.Outcome.Unreachable -> locally(login, password)
        }
    }

    /**
     * Sign in against the copy on this device.
     *
     * This is a first-class path, not a degraded one: the browser must not be
     * locked out by a network, and the profile fetched last time stays in place
     * and keeps working.
     */
    private fun locally(login: String, password: String): Result {
        val account = store.verifyLocally(login, password) ?: return Result.WrongCredentials
        store.signIn(account, null)
        val cached = store.vpnConfig
        cached?.let(::applyVpn)
        return Result.Success(account, cached != null)
    }

    /**
     * A profile arrived: fill the VPN in and arm it.
     *
     * Auto-connect goes on with it. The point of signing in was "it should just
     * work", and a tunnel that has to be switched on by hand after every
     * restart isn't that. It stays visible and switchable on the VPN screen.
     */
    private fun applyVpn(config: String) {
        val settings = components.vpnSettings
        if (settings.importFrom(config)) {
            settings.autoConnect = true
        }
    }

    /** Sign out: forget the account, its profile, and drop the tunnel. */
    suspend fun signOut() {
        components.vpn.disconnect()
        components.vpnSettings.clear()
        store.signOut()
    }
}
