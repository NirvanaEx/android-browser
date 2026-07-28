package com.upgrid.browser.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Who is signed in. Not a Google account — that one is separate and optional. */
data class Account(
    val login: String,
    val name: String,
    val role: String,
)

/**
 * The browser's own account.
 *
 * Separate from Google on purpose: Google is for syncing bookmarks, this is who
 * you are to *this* browser — and what it is allowed to set up for you. Right
 * now that means the VPN profile, which is the whole point: sign in and the
 * tunnel is configured, with no keys to carry from a server to a phone.
 *
 * Sign-in has two halves and either can carry it:
 *
 *  - **The account server**, if it answers. The credentials are checked by it
 *    (HTTP basic auth over TLS) and it hands back the profile. This is the path
 *    that keeps working when a profile changes: the phone re-fetches.
 *  - **Locally**, against a PBKDF2 hash stored on the device. Seeded once with
 *    the account the owner asked for, so the browser can be signed into before
 *    the server exists and while it is unreachable.
 *
 * Everything at rest — the password hash, the session, the cached profile — is
 * AES-256-GCM under a key generated in the Android keystore, the same scheme as
 * saved website passwords. See [com.upgrid.browser.logins.LoginStore].
 */
class AccountStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        seedDefaultAccount()
    }

    /** The signed-in account, or null. */
    val current: Account?
        get() {
            val json = read(KEY_SESSION) ?: return null
            return runCatching {
                val o = JSONObject(json)
                Account(
                    login = o.optString("login"),
                    name = o.optString("name").ifBlank { o.optString("login") },
                    role = o.optString("role", "user"),
                )
            }.getOrNull()
        }

    val isSignedIn: Boolean get() = current != null

    /**
     * The VPN profile this account was given, as `wg-quick` text. Null when the
     * account has none — the browser then behaves exactly as it did before.
     */
    val vpnConfig: String? get() = read(KEY_PROFILE)?.takeIf { it.isNotBlank() }

    /** Check a password locally. The server, when reachable, is authoritative. */
    fun verifyLocally(login: String, password: String): Account? {
        val json = read(KEY_ACCOUNTS) ?: return null
        val accounts = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val entry = accounts.optJSONObject(login.trim().lowercase()) ?: return null

        val salt = Base64.decode(entry.optString("salt"), Base64.NO_WRAP)
        val expected = entry.optString("hash")
        val actual = hash(password, salt, entry.optInt("iterations", ITERATIONS))
        if (expected != actual) return null

        return Account(
            login = login.trim().lowercase(),
            name = entry.optString("name").ifBlank { login },
            role = entry.optString("role", "user"),
        )
    }

    /** Remember a successful sign-in and whatever the server sent with it. */
    fun signIn(account: Account, vpnConfig: String?) {
        write(
            KEY_SESSION,
            JSONObject().apply {
                put("login", account.login)
                put("name", account.name)
                put("role", account.role)
            }.toString(),
        )
        if (vpnConfig != null) write(KEY_PROFILE, vpnConfig)
    }

    /**
     * Forget the account and the profile it brought.
     *
     * The profile goes with it: it belongs to the account, and leaving a
     * working tunnel behind after signing out would be a surprising thing for
     * "sign out" to mean.
     */
    fun signOut() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_PROFILE).apply()
    }

    /**
     * Add or replace a local account. Public so a future admin screen can use
     * it; today only [seedDefaultAccount] calls it.
     */
    fun putAccount(login: String, password: String, name: String, role: String) {
        val accounts = runCatching { JSONObject(read(KEY_ACCOUNTS).orEmpty()) }
            .getOrDefault(JSONObject())
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        accounts.put(
            login.trim().lowercase(),
            JSONObject().apply {
                put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                put("iterations", ITERATIONS)
                put("hash", hash(password, salt, ITERATIONS))
                put("name", name)
                put("role", role)
            },
        )
        write(KEY_ACCOUNTS, accounts.toString())
    }

    /**
     * The account the owner asked for, created on first launch.
     *
     * Written once and then left alone — re-seeding on every start would undo a
     * password change. It is a local gate, not a secret: what it actually
     * unlocks is on the account server, behind the same password.
     */
    private fun seedDefaultAccount() {
        if (read(KEY_ACCOUNTS) != null) return
        putAccount(DEFAULT_LOGIN, DEFAULT_PASSWORD, "Superadmin", "superadmin")
    }

    // --- Crypto ------------------------------------------------------------

    private fun hash(password: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2)
        return Base64.encodeToString(factory.generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    private fun write(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, storeKey())
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(key, Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP))
            .apply()
    }

    private fun read(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        return runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                storeKey(),
                GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)),
            )
            String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
        }.getOrElse {
            // Same reasoning as LoginStore: a key that can no longer decrypt its
            // own data (a device restore copies the file, not the keystore) must
            // not be able to stop the app from starting.
            Log.w(TAG, "Account data unreadable; clearing.", it)
            prefs.edit().remove(key).apply()
            null
        }
    }

    private fun storeKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "AccountStore"
        private const val FILE = "upgrid_account"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_SESSION = "session"
        private const val KEY_PROFILE = "profile"

        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "upgrid_account_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PBKDF2 = "PBKDF2WithHmacSHA256"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val ITERATIONS = 120_000

        /** The account the owner asked to have out of the box. */
        const val DEFAULT_LOGIN = "superadmin"
        private const val DEFAULT_PASSWORD = "123"
    }
}
