package com.upgrid.browser

import android.content.Context
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
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
                trackingProtectionPolicy = mozilla.components.concept.engine.EngineSession
                    .TrackingProtectionPolicy.recommended()
            )
        )
    }

    /**
     * Redux-style store holding tabs, history-in-flight, and selected tab id.
     *
     * `EngineMiddleware.create(engine)` is the bridge that turns store actions
     * (LoadUrl, AddTab, GoBack…) into actual engine session calls. Without it,
     * dispatching a `LoadUrlAction` throws "You need to add EngineMiddleware".
     */
    val store: BrowserStore by lazy {
        BrowserStore(middleware = EngineMiddleware.create(engine = engine))
    }

    /** HTTP client shared by feature-addons (downloading XPI) and other features. */
    val httpClient by lazy { OkHttpClient() }

    /** Use-cases facade for session operations: load URL, reload, go back, etc. */
    val sessionUseCases: SessionUseCases by lazy { SessionUseCases(store) }

    /** Use-cases facade for tab management: addTab, selectTab, removeTab. */
    val tabsUseCases: TabsUseCases by lazy { TabsUseCases(store) }

    /** Disk-backed session/tab persistence. Snapshot on pause, restore on cold start. */
    val sessionStorage: SessionStorage by lazy { SessionStorage(context, engine) }

    /** Favicon loader used by toolbar + tabs tray. */
    val icons: BrowserIcons by lazy { BrowserIcons(context, httpClient) }
}
