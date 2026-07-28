package com.upgrid.browser

import android.content.Context
import com.upgrid.browser.account.AccountStore
import com.upgrid.browser.bookmarks.BookmarkStore
import com.upgrid.browser.download.DownloadManager
import com.upgrid.browser.download.DownloadRecords
import com.upgrid.browser.download.FileNames
import com.upgrid.browser.errors.ErrorPageInterceptor
import com.upgrid.browser.fullscreen.VideoPlayerBridge
import com.upgrid.browser.history.HistoryStore
import com.upgrid.browser.logins.LoginStore
import com.upgrid.browser.search.SearchHistory
import com.upgrid.browser.tabs.TabThumbnails
import com.upgrid.browser.vpn.VpnController
import com.upgrid.browser.vpn.VpnSettings
import com.upgrid.browser.vpn.VpnStatus
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.engine.middleware.SessionPrioritizationMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.fetch.okhttp.OkHttpClient
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Single source of truth for long-lived browser objects.
 *
 * GeckoRuntime, the BrowserStore, and the Engine all expect to live for the whole
 * process lifetime — recreating them is expensive and fragile. We lazy-init them
 * once via [BrowserApplication] and hand the same instances to every Activity.
 */
class BrowserComponents(val context: Context) {

    /**
     * GeckoView's runtime: owns the content process(es) and shared state.
     * Must be created exactly once per process. We tune a few defaults here that
     * matter for an ad-blocking browser.
     */
    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)            // Lets advanced users edit prefs.
            .consoleOutput(BuildConfig.DEBUG)    // Forward web console to logcat in debug.
            .build()
        GeckoRuntime.create(context, settings)
    }

    /**
     * Mozilla's [Engine] abstraction over GeckoView. android-components features
     * (sessions, addons, downloads, etc.) talk to this — never to GeckoView directly.
     */
    val engine: Engine by lazy {
        GeckoEngine(
            context = context,
            runtime = runtime,
            defaultSettings = DefaultSettings(
                javascriptEnabled = true,
                domStorageEnabled = true,
                webFontsEnabled = true,
                automaticFontSizeAdjustment = true,
                trackingProtectionPolicy = EngineSession.TrackingProtectionPolicy.recommended(),
                // Asked the moment a response turns out to be a download, and
                // the only point at which Content-Disposition is still around.
                // Without a delegate the engine hands us a null filename and
                // every file would be saved as its URL's last path segment.
                downloadDelegate = FileNames,
                // What the user sees when a page doesn't arrive. Without it
                // the engine falls back to Firefox's own about:neterror, in
                // Gecko's build language, naming PR_CONNECT_RESET_ERROR at
                // somebody who typed an address and got nothing.
                requestInterceptor = ErrorPageInterceptor(context),
            )
        )
    }

    /**
     * Redux-style store holding tabs, history-in-flight, and selected tab id.
     *
     * `EngineMiddleware.create(engine)` is the bridge that turns store actions
     * (LoadUrl, AddTab, GoBack…) into actual engine session calls. Without it,
     * dispatching a `LoadUrlAction` throws "You need to add EngineMiddleware".
     *
     * [SessionPrioritizationMiddleware] is NOT part of that set and has to be
     * added by hand — and it is the single most important line in this file for
     * "I switched away for a minute and my page had to load again".
     *
     * GeckoView renders every page in a **separate content process**. Android
     * ranks processes and kills the cheapest-looking one first, and a content
     * process belonging to an app that isn't on screen looks very cheap: it is
     * large, it is idle, and nothing is bound to it. When it goes, the tab comes
     * back marked `crashed`, and the only way back is to recreate the session
     * and fetch the page again — which is exactly what the user sees.
     *
     * This middleware tells Gecko which tab the user is actually going to come
     * back to: the selected one is set to `PRIORITY_HIGH`, everything else to
     * default. Gecko passes that down to the process manager, which keeps the
     * high-priority child bound at foreground importance instead of letting it
     * drop to "empty" the moment the app is backgrounded. It is Mozilla's own
     * answer to this problem; we were simply not asking for it.
     *
     * It also raises the priority of a tab with half-filled form data for three
     * minutes after you leave — which needs `AppLifecycleAction` to be
     * dispatched; [BrowserApplication] does that.
     *
     * Last in the list on purpose: it reads `store.state` straight after
     * passing the action on, so it wants the reducer to have run.
     */
    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = EngineMiddleware.create(engine = engine) +
                SessionPrioritizationMiddleware(),
        )
    }

    /** HTTP client shared by feature-addons (downloading XPI) and other features. */
    val httpClient by lazy { OkHttpClient() }

    /** Use-cases facade for session operations: load URL, reload, go back, etc. */
    val sessionUseCases: SessionUseCases by lazy { SessionUseCases(store) }

    /** Use-cases facade for tab management: addTab, selectTab, removeTab. */
    val tabsUseCases: TabsUseCases by lazy { TabsUseCases(store) }

    /** Disk-backed session/tab persistence. Snapshot on pause, restore on cold start. */
    val sessionStorage: SessionStorage by lazy { SessionStorage(context, engine) }

    /** Favicon loader used by the toolbar, the tabs grid and the speed dial. */
    val icons: BrowserIcons by lazy { BrowserIcons(context, httpClient) }

    /**
     * The user's own data, process-wide.
     *
     * These used to be constructed per screen — MainActivity, the menu, each
     * sheet — and every instance opened its own `SQLiteOpenHelper`, i.e. its own
     * connection and its own page cache against the same file. Opening the
     * bookmarks list meant a second connection to a database the activity
     * already had open. One instance each, shared.
     */
    val bookmarks: BookmarkStore by lazy { BookmarkStore(context) }
    val browsingHistory: HistoryStore by lazy { HistoryStore(context) }
    val searchHistory: SearchHistory by lazy { SearchHistory(context) }

    /** Files fetched from pages, and the list of them. */
    val downloadRecords: DownloadRecords by lazy { DownloadRecords(context) }
    val downloads: DownloadManager by lazy {
        DownloadManager(context, store, httpClient, downloadRecords)
    }

    /** Saved logins, encrypted with a key that never leaves the device. */
    val logins: LoginStore by lazy { LoginStore(context) }

    /**
     * Page previews for the tabs grid. Memory only, and only filled while the
     * grid is the chosen view — see [com.upgrid.browser.tabs.TabThumbnails].
     */
    val tabThumbnails: TabThumbnails by lazy { TabThumbnails() }

    /**
     * The browser's own account — who is signed in, and what that sign-in
     * provisioned. Separate from the Google account, which only syncs data.
     */
    val accounts: AccountStore by lazy { AccountStore(context) }

    /**
     * The WireGuard tunnel and its profile. Process-wide because the tunnel
     * outlives every screen — and because two backends would fight over one
     * VpnService.
     */
    val vpnSettings: VpnSettings by lazy { VpnSettings(context) }
    val vpn: VpnController by lazy { VpnController(context) }

    /**
     * The tunnel's live readout — up/down plus the current speed. Sampled from
     * one place so the notification and the VPN screen can't disagree about
     * what the connection is doing.
     */
    val vpnStatus: VpnStatus by lazy { VpnStatus(vpn) }

    /**
     * Owns the built-in WebExtension behind the built-in video player.
     * [BrowserApplication.onCreate] calls `setupAndInstall()` once the engine
     * is ready; the bridge then has a port open for the lifetime of the app.
     */
    val videoPlayerBridge: VideoPlayerBridge by lazy {
        VideoPlayerBridge(this)
    }
}
