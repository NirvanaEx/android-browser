package com.upgrid.browser.account

import com.upgrid.browser.BrowserComponents
import com.upgrid.browser.prefs.BrowserPreferences

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
        /** Signed in, and the server handed over a profile. */
        data class Provisioned(val account: Account) : Result

        /** Signed in against the device. [cached] = an earlier profile is in place. */
        data class Offline(val account: Account, val cached: Boolean, val reason: String) : Result

        data object WrongCredentials : Result
    }

    val current: Account? get() = store.current

    suspend fun signIn(login: String, password: String): Result {
        val server = prefs.accountServer.trim()

        if (server.isNotBlank()) {
            when (val outcome = api.signIn(server, login, password)) {
                is AccountApi.Outcome.Success -> {
                    val provision = outcome.provision
                    store.signIn(provision.account, provision.vpnConfig)
                    provision.vpnConfig?.let(::applyVpn)
                    return Result.Provisioned(provision.account)
                }
                // The server is the authority when it answers: a rejected
                // password must not then be waved through by the local copy.
                AccountApi.Outcome.BadCredentials -> return Result.WrongCredentials
                is AccountApi.Outcome.Unreachable -> {
                    val account = store.verifyLocally(login, password)
                        ?: return Result.WrongCredentials
                    return offline(account, outcome.reason)
                }
            }
        }

        val account = store.verifyLocally(login, password) ?: return Result.WrongCredentials
        return offline(account, "")
    }

    private fun offline(account: Account, reason: String): Result {
        store.signIn(account, null)
        val cached = store.vpnConfig
        cached?.let(::applyVpn)
        return Result.Offline(account, cached != null, reason)
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
