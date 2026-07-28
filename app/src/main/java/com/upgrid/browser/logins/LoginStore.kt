package com.upgrid.browser.logins

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One saved sign-in. [host] is the site it belongs to, without `www.`. */
data class Login(
    val id: String,
    val host: String,
    val username: String,
    val password: String,
    val updatedAt: Long,
)

/**
 * Saved passwords.
 *
 * Encrypted with AES-256-GCM under a key generated in the Android keystore, so
 * the key material never enters this process's memory and cannot be read off
 * the device by anything that manages to read the file. The file itself is one
 * JSON document in app-private storage, encrypted whole: passwords are a list
 * you read all at once, and per-row encryption would leak the row count and
 * cost a keystore round-trip each.
 *
 * `androidx.security:security-crypto` does the same thing with more machinery;
 * this is fifty lines and one dependency fewer.
 *
 * If the key ever becomes unusable — a device-to-device restore copies the
 * file but not the keystore — decryption fails and the store resets itself.
 * That loses saved passwords, which is the only safe direction: the alternative
 * is an app that can't start because it can't read a file it must have.
 */
class LoginStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Every saved login, newest first. */
    fun all(): List<Login> = read().sortedByDescending { it.updatedAt }

    /** Logins for a site. [host] is matched exactly, after dropping `www.`. */
    fun forHost(host: String): List<Login> {
        val key = normaliseHost(host)
        if (key.isEmpty()) return emptyList()
        return read().filter { it.host == key }
    }

    fun count(): Int = read().size

    /**
     * Add or update. One row per (host, username): signing in again with a new
     * password replaces the old one rather than stacking a second row that
     * would then be offered at random.
     */
    fun save(host: String, username: String, password: String): Login? {
        val key = normaliseHost(host)
        if (key.isEmpty() || password.isEmpty()) return null

        val current = read().toMutableList()
        val existing = current.indexOfFirst { it.host == key && it.username == username }
        val login = Login(
            id = if (existing >= 0) current[existing].id else UUID.randomUUID().toString(),
            host = key,
            username = username,
            password = password,
            updatedAt = System.currentTimeMillis(),
        )
        if (existing >= 0) current[existing] = login else current += login
        write(current.takeLast(MAX_ROWS))
        return login
    }

    /** True when this exact pair is already stored — nothing to offer, then. */
    fun contains(host: String, username: String, password: String): Boolean {
        val key = normaliseHost(host)
        return read().any { it.host == key && it.username == username && it.password == password }
    }

    fun delete(id: String) {
        write(read().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    // --- Storage -----------------------------------------------------------

    private fun read(): List<Login> {
        val blob = prefs.getString(KEY, null) ?: return emptyList()
        val json = runCatching { decrypt(blob) }.getOrElse {
            Log.w(TAG, "Saved logins are unreadable; resetting.", it)
            clear()
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Login(
                    id = o.optString("id"),
                    host = o.optString("host"),
                    username = o.optString("user"),
                    password = o.optString("pass"),
                    updatedAt = o.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(logins: List<Login>) {
        val array = JSONArray()
        logins.forEach { login ->
            array.put(
                JSONObject().apply {
                    put("id", login.id)
                    put("host", login.host)
                    put("user", login.username)
                    put("pass", login.password)
                    put("at", login.updatedAt)
                },
            )
        }
        prefs.edit().putString(KEY, encrypt(array.toString())).apply()
    }

    // --- Crypto ------------------------------------------------------------

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // iv ‖ ciphertext. The IV is generated by the keystore and must be kept
        // with the payload; it is not a secret.
        val out = cipher.iv + body
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_BYTES)
        val body = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
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
        private const val TAG = "LoginStore"
        private const val FILE = "upgrid_logins"
        private const val KEY = "vault"
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "upgrid_logins_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM's standard nonce length, which is what the keystore generates. */
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** More than anyone types by hand; keeps one bad page from filling it. */
        private const val MAX_ROWS = 500

        /** `https://www.GitHub.com/login` → `github.com`. */
        fun normaliseHost(hostOrUrl: String): String {
            val value = hostOrUrl.trim()
            if (value.isEmpty()) return ""
            val host = if (value.contains("://")) {
                runCatching { Uri.parse(value).host }.getOrNull().orEmpty()
            } else {
                value
            }
            return host.lowercase().removePrefix("www.")
        }
    }
}
