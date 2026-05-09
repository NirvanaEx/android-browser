package com.upgrid.browser

import com.upgrid.browser.addons.AdblockBootstrap
import kotlinx.coroutines.suspendCancellableCoroutine
import mozilla.components.concept.engine.webextension.WebExtension
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin facade for the menu's "AdBlock: ON/OFF" toggle.
 *
 * We don't expose addon management to the user — we only let them turn the
 * single bundled adblocker on or off. Reads state synchronously from the
 * engine's last known list (cheap; cached in process), toggles asynchronously.
 *
 * If uBO is mid-install or wasn't installed yet (offline first-launch),
 * [isEnabled] returns `false` and [toggle] kicks off a fresh install.
 */
class AdblockController(private val components: BrowserComponents) {

    /**
     * Suspending snapshot of whether uBO is currently installed *and* enabled.
     *
     * The previous sync version assumed `listInstalledWebExtensions`'s
     * callback fires synchronously when the engine's in-memory map is warm.
     * That isn't reliable on cold start — the callback can land on the next
     * dispatch tick, leaving the caller with `result = false` even when uBO
     * is installed and enabled. Symptom: the bottom-bar shield rendered as
     * "OFF" while uBO was actually blocking ads. Going through a coroutine
     * makes this race impossible.
     */
    suspend fun isEnabled(): Boolean = findUbo()?.isEnabled() == true

    /** Flip the current state. If uBO isn't installed, install + enable it. */
    suspend fun toggle() {
        val ubo = findUbo()
        if (ubo == null) {
            // Not installed yet — install (which also enables).
            AdblockBootstrap(components, components.context).ensureInstalled()
            return
        }
        if (ubo.isEnabled()) disable(ubo) else enable(ubo)
    }

    private suspend fun findUbo(): WebExtension? = suspendCancellableCoroutine { cont ->
        components.engine.listInstalledWebExtensions(
            onSuccess = { exts -> cont.resume(exts.firstOrNull { it.id == AdblockBootstrap.UBO_ID }) },
            onError = { t -> cont.resumeWithException(t) }
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

}
