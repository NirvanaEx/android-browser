package com.upgrid.browser.vpn

import android.content.Context

/**
 * The WireGuard profile, field by field.
 *
 * Stored as fields rather than as one blob of `wg-quick` text so the settings
 * screen can be a form: the user changes DNS or the endpoint without editing a
 * config file on a phone keyboard. The text is generated from the fields when
 * the tunnel comes up, and pasting a config file goes the other way — see
 * [importFrom].
 *
 * Defaults are the ones that make a phone VPN work at all: everything routed,
 * a keepalive so the connection survives NAT, and an MTU that leaves room for
 * WireGuard's own header inside a typical 1500-byte path.
 */
class VpnSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * This app's own package, which is what [browserOnly] puts on the tunnel's
     * application list. Read from the context rather than written down: the
     * debug build is `com.upgrid.browser.debug` and a hard-coded string would
     * quietly route nothing at all there.
     */
    private val appPackage = context.applicationContext.packageName

    var privateKey: String
        get() = prefs.getString(KEY_PRIVATE, "").orEmpty()
        set(value) = put(KEY_PRIVATE, value)

    /** This device's address inside the tunnel, e.g. `10.8.0.2/32`. */
    var address: String
        get() = prefs.getString(KEY_ADDRESS, "").orEmpty()
        set(value) = put(KEY_ADDRESS, value)

    var dns: String
        get() = prefs.getString(KEY_DNS, DEFAULT_DNS).orEmpty()
        set(value) = put(KEY_DNS, value)

    var peerPublicKey: String
        get() = prefs.getString(KEY_PEER, "").orEmpty()
        set(value) = put(KEY_PEER, value)

    /** Optional extra symmetric key; blank when the server doesn't use one. */
    var presharedKey: String
        get() = prefs.getString(KEY_PSK, "").orEmpty()
        set(value) = put(KEY_PSK, value)

    /** `host:port` of the server. */
    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "").orEmpty()
        set(value) = put(KEY_ENDPOINT, value)

    var allowedIps: String
        get() = prefs.getString(KEY_ALLOWED, DEFAULT_ALLOWED).orEmpty()
        set(value) = put(KEY_ALLOWED, value)

    var mtu: String
        get() = prefs.getString(KEY_MTU, DEFAULT_MTU).orEmpty()
        set(value) = put(KEY_MTU, value)

    var keepalive: String
        get() = prefs.getString(KEY_KEEPALIVE, DEFAULT_KEEPALIVE).orEmpty()
        set(value) = put(KEY_KEEPALIVE, value)

    /**
     * Route this browser through the tunnel and leave the rest of the phone
     * alone. On by default — this is a browser's VPN, not the phone's.
     *
     * Android has no per-app VPN below the VpnService: whoever holds the
     * service still holds the phone's only VPN slot and the system's key icon
     * still appears. What it does have is an application list, and a tunnel
     * built with one carries the listed packages and nothing else — every other
     * app keeps using the normal connection, including while the tunnel is up.
     * That is expressed in the config as `IncludedApplications`, which the
     * wireguard-android backend turns into `VpnService.Builder`
     * `.addAllowedApplication`.
     *
     * Turning it off is the old behaviour, the whole phone, and it is worth
     * knowing what that costs: with `AllowedIPs = 0.0.0.0/0, ::/0` and one peer
     * the backend deliberately builds a kill-switch, so a tunnel that is up but
     * not passing traffic takes the phone's connectivity down with it. Scoped
     * to the browser, the same failure is a browser that can't load pages while
     * everything else on the phone works.
     */
    var browserOnly: Boolean
        get() = prefs.getBoolean(KEY_BROWSER_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BROWSER_ONLY, value).apply()
        }

    /** Bring the tunnel up as soon as the app starts. Off by default. */
    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO, value).apply()
        }

    /**
     * Undo the auto-connect that signing in used to switch on by itself.
     *
     * Up to and including 0.2.0.27, `AccountController.applyVpn` set
     * [autoConnect] as part of provisioning the profile. Nobody asked for it,
     * and the result is a browser that raises a VPN on every launch — including
     * the launch right after an update, which is where it gets noticed. That
     * line is gone, but the value it wrote is in SharedPreferences on every
     * phone that ever signed in, and removing the writer does not remove the
     * value.
     *
     * So: clear it once, and remember that it was cleared. The switch on the
     * VPN screen still works and is still honoured — it just has to be the user
     * who moves it.
     */
    fun forgetAutoConnectSetBySignIn() {
        if (prefs.getBoolean(KEY_AUTO_RESET, false)) return
        prefs.edit()
            .putBoolean(KEY_AUTO_RESET, true)
            .putBoolean(KEY_AUTO, false)
            .apply()
    }

    /** Enough of a profile to try connecting with. */
    val isConfigured: Boolean
        get() = privateKey.isNotBlank() && peerPublicKey.isNotBlank() &&
            endpoint.isNotBlank() && address.isNotBlank()

    /**
     * The profile as a `wg-quick` file — the format the tunnel library parses
     * and the format every WireGuard server hands out, so this doubles as the
     * thing to show the user when they ask what is configured.
     */
    fun toWgQuick(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${privateKey.trim()}")
        appendLine("Address = ${address.trim()}")
        if (dns.isNotBlank()) appendLine("DNS = ${dns.trim()}")
        if (mtu.isNotBlank()) appendLine("MTU = ${mtu.trim()}")
        // Written here rather than passed to the backend separately because
        // the backend has no other way in: it reads the application list off
        // the parsed config, like every other interface setting.
        if (browserOnly) appendLine("IncludedApplications = $appPackage")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${peerPublicKey.trim()}")
        if (presharedKey.isNotBlank()) appendLine("PresharedKey = ${presharedKey.trim()}")
        appendLine("Endpoint = ${endpoint.trim()}")
        appendLine("AllowedIPs = ${allowedIps.trim().ifBlank { DEFAULT_ALLOWED }}")
        if (keepalive.isNotBlank()) appendLine("PersistentKeepalive = ${keepalive.trim()}")
    }

    /**
     * Fill the fields from a pasted `wg-quick` config.
     *
     * Hand-parsed rather than round-tripped through the library's own parser:
     * that one answers with a validated [com.wireguard.config.Config] whose
     * getters hand back Optionals of parsed types, and turning those back into
     * the strings a text field wants is more code than reading an INI file —
     * which is all this format is. Validation still happens, at connect time,
     * where a bad value can actually be reported as one.
     *
     * Returns false when the text carries neither of the two keys that make a
     * profile, i.e. when it isn't a config at all.
     */
    fun importFrom(text: String): Boolean {
        var section = ""
        var found = false
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[")) {
                section = line.trim('[', ']').lowercase()
                return@forEach
            }
            val name = line.substringBefore('=', "").trim().lowercase()
            val value = line.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) return@forEach

            when {
                section == "interface" && name == "privatekey" -> {
                    privateKey = value; found = true
                }
                section == "interface" && name == "address" -> address = value
                section == "interface" && name == "dns" -> dns = value
                section == "interface" && name == "mtu" -> mtu = value
                section == "peer" && name == "publickey" -> {
                    peerPublicKey = value; found = true
                }
                section == "peer" && name == "presharedkey" -> presharedKey = value
                section == "peer" && name == "endpoint" -> endpoint = value
                section == "peer" && name == "allowedips" -> allowedIps = value
                section == "peer" && name == "persistentkeepalive" -> keepalive = value
            }
        }
        return found
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun put(key: String, value: String) {
        prefs.edit().putString(key, value.trim()).apply()
    }

    companion object {
        private const val FILE = "upgrid_vpn"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_ADDRESS = "address"
        private const val KEY_DNS = "dns"
        private const val KEY_PEER = "peer_public_key"
        private const val KEY_PSK = "preshared_key"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_ALLOWED = "allowed_ips"
        private const val KEY_MTU = "mtu"
        private const val KEY_KEEPALIVE = "keepalive"
        private const val KEY_AUTO = "auto_connect"

        /** Only this app goes through the tunnel. See [browserOnly]. */
        private const val KEY_BROWSER_ONLY = "browser_only"

        /** Marks [forgetAutoConnectSetBySignIn] as already done on this phone. */
        private const val KEY_AUTO_RESET = "auto_connect_reset"

        const val DEFAULT_DNS = "1.1.1.1, 1.0.0.1"

        /** Everything through the tunnel — the only setting most people want. */
        const val DEFAULT_ALLOWED = "0.0.0.0/0, ::/0"

        /**
         * 1420 = 1500 − 20 (IPv4) − 8 (UDP) − 32 (WireGuard). Mobile networks
         * with a smaller path exist; that's why this is a field.
         */
        const val DEFAULT_MTU = "1420"

        /** Keeps the NAT mapping on a mobile carrier alive between packets. */
        const val DEFAULT_KEEPALIVE = "25"
    }
}
