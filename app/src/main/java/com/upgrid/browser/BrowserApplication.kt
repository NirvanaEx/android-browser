package com.upgrid.browser

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.upgrid.browser.addons.AdblockBootstrap
import com.upgrid.browser.prefs.BrowserPreferences
import com.upgrid.browser.ui.ThemeMode
import com.upgrid.browser.ui.applyColorScheme
import com.upgrid.browser.vpn.VpnNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.RestoreCompleteAction
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.browser.state.action.TabListAction

/**
 * App-wide entry point. Owns [BrowserComponents], restores the previous session
 * on cold start, and kicks off the silent uBlock Origin install.
 *
 * Important: GeckoView spawns several child processes (content, crash helper,
 * GPU). Each one re-runs [onCreate]. Creating GeckoRuntime there throws
 * "GeckoRuntime is shutting down" because the runtime is process-shared and
 * child processes must not (re-)init it. We early-out via [isMainProcess].
 */
class BrowserApplication : Application() {

    val components: BrowserComponents by lazy { BrowserComponents(this) }

    private val preferences by lazy { BrowserPreferences(this) }

    /**
     * Main-thread scope. android-components engine APIs (esp. GeckoView's
     * `WebExtensionController.list`/`install`) require a Looper-attached thread,
     * so anything that touches the engine must run here, not on Dispatchers.Default.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // GeckoView's child processes call Application.onCreate too — skip there.
        if (!isMainProcess()) return

        // Before any Activity exists: setDefaultNightMode after one is on screen
        // recreates it, and a browser that visibly relaunches itself on every
        // cold start looks broken.
        ThemeMode.apply(preferences.themeMode)

        // Touching `components` here forces GeckoRuntime creation now rather than
        // on first Activity#onCreate — keeps the first navigation snappy.
        components.runtime
        components.engine.applyColorScheme(preferences.themeMode)
        restorePreviousSession()

        // Install the bundled player-helper WebExtension. Touching the
        // bridge property creates it; setupAndInstall() then registers the
        // extension with the engine and primes the native messaging port.
        // Idempotent — re-installing an already-installed extension is a
        // no-op in a-c.
        components.videoPlayerBridge.setupAndInstall()

        // Watches the store for responses the engine decided it can't render.
        // Process-wide rather than per-Activity: a download has to survive the
        // screen rotating and the app going to the background.
        components.downloads.start()

        appScope.launch {
            AdblockBootstrap(components, this@BrowserApplication).ensureInstalled()
        }

        // Bring the tunnel up if the user asked for it. Silent on failure: the
        // one likely cause is the system's VPN consent never having been given,
        // and there is no Activity here to ask from — the menu row and the VPN
        // screen both do it properly.
        if (components.vpnSettings.autoConnect && components.vpnSettings.isConfigured) {
            appScope.launch { components.vpn.connect(components.vpnSettings) }
        }

        // The tunnel's own notification. Here rather than in an Activity
        // because the tunnel outlives every screen — the notification has to
        // still be right after the browser is swiped out of the recents list,
        // and it is the only way back to a disconnect button from there.
        //
        // It renders off the sampled status rather than off the raw tunnel
        // state, because "connected" is not the interesting half: a tunnel
        // that is up and carrying nothing looks exactly like a healthy one
        // until you can see the speed.
        val notifications = VpnNotifications(this)
        components.vpnStatus.start(appScope)
        appScope.launch {
            components.vpnStatus.state.collect { notifications.render(it) }
        }

        attachAutosave(components.sessionStorage)
    }

    /**
     * Hand memory back before the system takes the whole process.
     *
     * This is the one lever an app has over "I switched away for a second and
     * my page had to load again". Android kills what it needs to; the browser's
     * job is to be worth less than it costs to kill. Without this the store
     * keeps a live engine session for every tab that has ever been rendered —
     * the middleware that suspends the ones nobody is looking at is already
     * installed by [BrowserComponents], it just never hears about the pressure
     * because nothing dispatches the action it listens for.
     *
     * Guarded by [isMainProcess] for the same reason [onCreate] is, and it
     * matters more here: GeckoView's content processes get this callback too,
     * `components` is lazy, and touching it there would build a second
     * BrowserStore and a second GeckoEngine inside a child process — the exact
     * thing the whole file exists to prevent, triggered by low memory.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isMainProcess()) return
        runCatching { components.icons.onTrimMemory(level) }
        runCatching { components.store.dispatch(SystemAction.LowMemoryAction(level)) }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            runCatching { components.tabThumbnails.clear() }
        }
    }

    /**
     * Pull last snapshot from disk into the store; no-op on truly first launch.
     *
     * `BrowserStore.dispatch` returns Unit (synchronous, not a Job) in lib-state
     * 150.x — no `.join()`. The actions feed sequentially through the reducer.
     */
    private fun restorePreviousSession() {
        try {
            val snapshot = components.sessionStorage.restore()
            if (snapshot != null && snapshot.tabs.isNotEmpty()) {
                components.store.dispatch(
                    TabListAction.RestoreAction(
                        tabs = snapshot.tabs,
                        selectedTabId = snapshot.selectedTabId,
                        restoreLocation = TabListAction.RestoreAction.RestoreLocation.BEGINNING,
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Session restore failed; starting fresh.", t)
        } finally {
            try {
                components.store.dispatch(RestoreCompleteAction)
            } catch (t: Throwable) {
                Log.w(TAG, "RestoreCompleteAction dispatch failed.", t)
            }
        }
    }

    /**
     * Persist tabs whenever the session list changes (open/close/navigate).
     *
     * `whenSessionsChange()` is the cheapest fan-out — it diffs internally before
     * writing. For periodic saves you'd add `.periodicallyInForeground(...)` but
     * that overload requires a scheduler/lifecycle and isn't worth it for MVP.
     */
    private fun attachAutosave(storage: SessionStorage) {
        storage.autoSave(components.store)
            .whenGoingToBackground()
            .whenSessionsChange()
    }

    /**
     * True iff this Application instance is in the process whose name equals our
     * package — i.e. the "main" app process, not a GeckoView content/crash/GPU
     * helper. Uses ActivityManager because [Application.getProcessName] is API 28+
     * and we target minSdk 26.
     */
    private fun isMainProcess(): Boolean {
        val pid = android.os.Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val procs = am.runningAppProcesses ?: return true
        return procs.any { it.pid == pid && it.processName == packageName }
    }

    companion object {
        private const val TAG = "BrowserApplication"
    }
}
