package org.mozilla.fenix.upgrid

import android.util.Log
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.addons.AddonManager

/** Uses Fenix's extension delegate, store and updater; never replaces their handlers. */
class UpgridAdblock(private val engine: Engine, private val addons: AddonManager) {
    private var started = false

    fun ensureInstalled() {
        if (started) return
        started = true
        engine.listInstalledWebExtensions(
            onSuccess = { extensions ->
                // Preserve the user's disabled state on later launches.
                if (extensions.none { it.id == ID }) install()
            },
            onError = { Log.w(TAG, "Cannot query uBO; will retry next launch", it) },
        )
    }

    private fun install() {
        addons.installAddon(
            url = XPI_URL,
            onSuccess = { addon ->
                if (addon.id == ID && !addon.isEnabled()) {
                    addons.enableAddon(
                        addon,
                        onError = { Log.w(TAG, "Cannot enable newly installed uBO", it) },
                    )
                }
                Log.i(TAG, "uBO installed; automatic updates registered")
            },
            onError = { Log.w(TAG, "uBO installation failed; will retry next launch", it) },
        )
    }

    companion object {
        private const val TAG = "UpgridAdblock"
        const val ID = "uBlock0@raymondhill.net"
        const val XPI_URL = "https://addons.mozilla.org/firefox/downloads/file/4981431/ublock_origin-1.74.0.xpi"
    }
}
