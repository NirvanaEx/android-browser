package com.upgrid.browser.addons

import android.content.Context
import android.util.Log
import com.upgrid.browser.BrowserComponents
import com.upgrid.browser.prefs.BrowserPreferences
import kotlinx.coroutines.suspendCancellableCoroutine
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionDelegate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Installs and keeps uBlock Origin enabled, silently.
 *
 * Strategy:
 *  - Register a WebExtensionDelegate on the engine that auto-grants install /
 *    update / optional-permission requests. Without this, GeckoView aborts
 *    the permission prompt query and the addon ends up loaded-but-crippled —
 *    classic symptom: filters mostly work but pop-ups / cosmetic rules don't.
 *  - On first launch, install the AMO-signed uBO XPI directly via the engine.
 *  - On subsequent launches, query installed extensions; if uBO is missing
 *    (user reset, app reinstall, etc.) — install again.
 *  - After install, explicitly call enableWebExtension as a safety net — some
 *    a-c versions land the install in disabled state when the prompt was
 *    suppressed, and re-enabling is a cheap idempotent op.
 *  - We never expose the addons-management UI in the MVP. uBO has its own
 *    settings page at `chrome://ublock0` which we can link to from a settings
 *    screen later. For now, the toggle in the app menu calls enable/disable.
 *
 * **On by default, not on regardless.** The safety net above used to run
 * unconditionally, which meant it also undid the user's own decision: turn
 * AdBlock off in the menu, restart the browser, and it was back on with no
 * explanation. [BrowserPreferences.adblockEnabled] records what the user chose
 * — it starts true, so a fresh install still blocks ads without being asked —
 * and the re-enable only fires when the answer is still yes.
 *
 * The XPI URL is the AMO direct download. Mozilla rotates the file id for each
 * uBO release; if AMO returns 404, bump the URL via [UBO_AMO_PAGE].
 */
class AdblockBootstrap(
    private val components: BrowserComponents,
    private val context: Context,
) {
    private val preferences by lazy { BrowserPreferences(context) }

    suspend fun ensureInstalled() {
        registerAutoGrantDelegate()

        val wanted = preferences.adblockEnabled
        val existing = listInstalled().firstOrNull { it.id == UBO_ID }
        if (existing != null) {
            Log.i(TAG, "uBO already installed: enabled=${existing.isEnabled()} version=${existing.getMetadata()?.version}")
            // Bring the engine back in line with the user's choice — in either
            // direction. A permission-prompt abort can leave it disabled when
            // it should be on; a reinstall can leave it on when it was turned
            // off.
            if (!existing.isEnabled() && wanted) {
                runCatching { enable(existing) }
                    .onSuccess { Log.i(TAG, "Re-enabled previously-disabled uBO.") }
                    .onFailure { Log.w(TAG, "Failed to re-enable uBO.", it) }
            } else if (existing.isEnabled() && !wanted) {
                runCatching { disable(existing) }
                    .onSuccess { Log.i(TAG, "uBO left off, as chosen.") }
                    .onFailure { Log.w(TAG, "Failed to disable uBO.", it) }
            }
            return
        }

        try {
            val ext = installFromUrl(UBO_XPI_URL)
            Log.i(TAG, "Installed uBO: id=${ext.id} version=${ext.getMetadata()?.version} enabled=${ext.isEnabled()}")
            // Belt-and-braces: if install left it disabled, flip it on now.
            if (!ext.isEnabled() && wanted) {
                runCatching { enable(ext) }
                    .onSuccess { Log.i(TAG, "Enabled freshly-installed uBO.") }
                    .onFailure { Log.w(TAG, "Failed to enable uBO post-install.", it) }
            }
        } catch (t: Throwable) {
            // Don't crash the browser if AMO is unreachable on first launch — the
            // user just gets an unfiltered session. We retry on next launch.
            Log.w(TAG, "Silent uBO install failed; will retry on next launch.", t)
        }
    }

    /**
     * Auto-grant install/update permission prompts so uBO comes up fully
     * privileged without ever showing UI to the user. We only auto-grant for
     * our pinned uBO id — anything else (future bundled add-ons we haven't
     * vetted) falls through to the engine default (deny).
     *
     * Idempotent — the engine replaces any previously registered delegate, so
     * calling this on every cold start is safe.
     */
    private fun registerAutoGrantDelegate() {
        components.engine.registerWebExtensionDelegate(object : WebExtensionDelegate {
            override fun onInstallPermissionRequest(
                extension: WebExtension,
                permissions: List<String>,
                origins: List<String>,
                dataCollectionPermissions: List<String>,
                onConfirm: (PermissionPromptResponse) -> Unit,
            ) {
                val grant = extension.id == UBO_ID
                Log.i(
                    TAG,
                    "onInstallPermissionRequest id=${extension.id} " +
                        "perms=$permissions origins=$origins data=$dataCollectionPermissions → grant=$grant"
                )
                // Grant all three: standard permissions, private-mode access, and
                // technical/interaction data. Without private-mode + tech data,
                // uBO can't filter in incognito tabs and may skip telemetry-driven
                // anti-circumvention rules. Safe for our locked-down use case.
                onConfirm(
                    PermissionPromptResponse(
                        isPermissionsGranted = grant,
                        isPrivateModeGranted = grant,
                        isTechnicalAndInteractionDataGranted = grant,
                    )
                )
            }
        })
    }

    private suspend fun listInstalled(): List<WebExtension> = suspendCancellableCoroutine { cont ->
        components.engine.listInstalledWebExtensions(
            onSuccess = { exts -> cont.resume(exts) },
            onError = { throwable -> cont.resumeWithException(throwable) }
        )
    }

    private suspend fun installFromUrl(url: String): WebExtension = suspendCancellableCoroutine { cont ->
        components.engine.installWebExtension(
            url = url,
            onSuccess = { ext -> cont.resume(ext) },
            onError = { throwable -> cont.resumeWithException(throwable) }
        )
    }

    private suspend fun enable(ext: WebExtension): WebExtension = suspendCancellableCoroutine { cont ->
        components.engine.enableWebExtension(
            extension = ext,
            onSuccess = { cont.resume(it) },
            onError = { t -> cont.resumeWithException(t) }
        )
    }

    private suspend fun disable(ext: WebExtension): WebExtension = suspendCancellableCoroutine { cont ->
        components.engine.disableWebExtension(
            extension = ext,
            onSuccess = { cont.resume(it) },
            onError = { t -> cont.resumeWithException(t) }
        )
    }

    companion object {
        private const val TAG = "AdblockBootstrap"

        /** AMO listing page — visit in a browser to copy a fresh XPI URL when bumping. */
        const val UBO_AMO_PAGE = "https://addons.mozilla.org/firefox/addon/ublock-origin/"

        /**
         * Direct download for uBO 1.70.0 (Mozilla-signed XPI). Bump when uBO releases.
         * The file id (4721638) changes each release; the version in the filename is cosmetic.
         */
        const val UBO_XPI_URL =
            "https://addons.mozilla.org/firefox/downloads/file/4721638/ublock_origin-1.70.0.xpi"

        /** Stable extension id from uBO's manifest.json — used to detect "already installed". */
        const val UBO_ID = "uBlock0@raymondhill.net"
    }
}
