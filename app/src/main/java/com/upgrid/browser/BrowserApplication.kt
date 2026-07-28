package com.upgrid.browser

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.upgrid.browser.addons.AdblockBootstrap
import com.upgrid.browser.prefs.BrowserPreferences
import com.upgrid.browser.ui.ThemeMode
import com.upgrid.browser.ui.applyColorScheme
import com.upgrid.browser.vpn.VpnNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.AppLifecycleAction
import mozilla.components.browser.state.action.RestoreCompleteAction
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.browser.state.action.TabListAction
import java.util.concurrent.TimeUnit

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

        // Bring the tunnel up if the user asked for it — and only if the *user*
        // asked, which is what forgetAutoConnectSetBySignIn() is for: an older
        // build armed this switch by itself the moment you signed in, so on
        // these phones "connect on start" is on without anyone having chosen
        // it. Cleared once, then the switch means what it says.
        //
        // Silent on failure: the one likely cause is the system's VPN consent
        // never having been given, and there is no Activity here to ask from —
        // the menu row and the VPN screen both do it properly.
        components.vpnSettings.forgetAutoConnectSetBySignIn()
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
        watchAppLifecycle()
    }

    /**
     * Tell the store when the whole app comes and goes.
     *
     * Two different things need this, and neither has an Activity to hang off:
     *
     *  - [mozilla.components.browser.state.engine.middleware.SessionPrioritizationMiddleware]
     *    asks the selected tab whether it is holding form data at the moment
     *    the app is backgrounded, and keeps that tab at high priority for three
     *    minutes if it is. A half-filled form is the one thing a reload
     *    genuinely destroys.
     *  - The snapshot on disk. android-components writes it the instant the app
     *    stops, but the *engine* state — scroll position, back/forward history,
     *    what was typed into the page — is asked for at that same moment and
     *    answered a beat later, by Gecko, on its own thread. The write that
     *    fires first therefore persists the state from before, and after a kill
     *    the browser comes back one step older than it should be. So: write
     *    again once the answer has had time to land.
     *
     * ProcessLifecycleOwner rather than an Activity callback because both of
     * these are about the *app* going away, not a screen — rotating the phone
     * or opening the tabs list must not count.
     */
    private fun watchAppLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    runCatching { components.store.dispatch(AppLifecycleAction.ResumeAction) }
                }

                override fun onPause(owner: LifecycleOwner) {
                    runCatching { components.store.dispatch(AppLifecycleAction.PauseAction) }
                }

                override fun onStop(owner: LifecycleOwner) {
                    saveSessionOnceGeckoHasAnswered()
                }
            },
        )
    }

    /** See [watchAppLifecycle]: the second write, after the engine has replied. */
    private fun saveSessionOnceGeckoHasAnswered() {
        appScope.launch {
            delay(SESSION_FLUSH_GRACE_MS)
            withContext(Dispatchers.IO) {
                runCatching { components.sessionStorage.save(components.store.state) }
            }
        }
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
     * Persist tabs whenever the session list changes, on a timer, and on the
     * way to the background.
     *
     * The snapshot is what the browser wakes up with after Android kills the
     * process, and how good it is decides whether "I switched apps for a
     * minute" costs the page or not. Each of the three catches a different
     * loss:
     *
     *  - **whenSessionsChange** — tabs opened, closed, navigated. Cheap; it
     *    diffs internally before writing.
     *  - **periodicallyInForeground** — a page you have been reading, and
     *    scrolling, without navigating. Nothing above fires for that, so
     *    without the timer the saved copy could be an hour older than the
     *    screen. Half a minute is a serialise of a few kilobytes on a
     *    background thread.
     *  - **whenGoingToBackground** — the last moment before the process becomes
     *    a candidate for being killed, which is exactly when it matters.
     */
    private fun attachAutosave(storage: SessionStorage) {
        storage.autoSave(components.store)
            .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
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

        /**
         * How long to wait after the app stops before writing the snapshot a
         * second time.
         *
         * Long enough for Gecko's round trip (releasing the view asks the
         * session to flush its state, and the answer comes back through the
         * store), short enough that a process being killed for backgrounding
         * has not usually got there yet.
         */
        private const val SESSION_FLUSH_GRACE_MS = 1_500L
    }
}
