package com.upgrid.browser

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.upgrid.browser.databinding.ActivityMainBinding
import com.upgrid.browser.home.QuickLink
import com.upgrid.browser.home.StartPagePresenter
import com.upgrid.browser.menu.AppMenuPopup
import com.upgrid.browser.tabs.TabsTrayFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.feature.findinpage.FindInPageFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.toolbar.ToolbarFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper

/**
 * Single-activity browser. Phase 2: BrowserStore-driven, multi-tab.
 *
 * The store is the source of truth for tabs and the current URL. We attach
 * lifecycle-scoped feature wrappers from android-components that wire the store
 * to the visible UI:
 *
 *  - [SessionFeature]      — renders the selected tab's EngineSession into the EngineView
 *  - [ToolbarFeature]      — keeps the toolbar URL/title/progress bound to the selected tab
 *  - [FindInPageFeature]   — wires the find-in-page bar to the engine session
 *
 * Three observers on the store flow drive the rest of the UI:
 *  - tab count + nav button enabled-state (existing).
 *  - selected tab URL → toggle the speed-dial overlay vs engine view.
 *  - selected tab mediaSessionState → toggle the floating PiP button.
 *
 * If the store has zero tabs at startup we open one to [HOME_URL]; on cold
 * start [BrowserApplication] tries to restore the previous session first.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var startPage: StartPagePresenter
    private val components get() = (application as BrowserApplication).components

    /** Cached uBO façade. Used by the bottom-bar shield button for instant toggle. */
    private val adblock by lazy { AdblockController(components) }

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val toolbarFeature = ViewBoundFeatureWrapper<ToolbarFeature>()
    private val findInPageFeature = ViewBoundFeatureWrapper<FindInPageFeature>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureAtLeastOneTab(initialUrl = intent.dataStringIfView() ?: HOME_URL)

        startPage = StartPagePresenter(binding.startPage) { link -> onQuickLinkClick(link) }
        wireToolbar()
        wireFeatures()
        wireBottomBar()
        wirePipFab()
        wireBackPress()
        observeStore()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataStringIfView()?.let {
            // External link intent → always open in a new tab and select it.
            components.tabsUseCases.addTab(url = it, selectTab = true)
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch state drift from sources that don't notify us — e.g. user
        // toggled uBO from the menu popup before backgrounding the app.
        if (::binding.isInitialized) renderAdblockShield()
    }

    // --- View wiring -------------------------------------------------------

    private fun wireToolbar() {
        binding.toolbar.display.hint = getString(R.string.omnibar_hint)
        binding.toolbar.edit.hint = getString(R.string.omnibar_hint)
        // Hide the synthetic about:blank URL so the start page reads as a clean
        // search entry instead of "your-tab-is-on-about:blank".
        binding.toolbar.display.urlFormatter = { url ->
            if (url.toString() == HOME_URL || url.isBlank()) "" else url
        }
        // Banana-style left-of-URL home button. Goes to about:blank so the
        // speed-dial overlay shows; observeStore() handles that toggle.
        binding.btnTopHome.setOnClickListener { components.sessionUseCases.loadUrl(HOME_URL) }
    }

    private fun wireFeatures() {
        sessionFeature.set(
            feature = SessionFeature(
                store = components.store,
                goBackUseCase = components.sessionUseCases.goBack,
                goForwardUseCase = components.sessionUseCases.goForward,
                engineView = binding.engineView,
            ),
            owner = this,
            view = binding.root,
        )

        toolbarFeature.set(
            feature = ToolbarFeature(
                toolbar = binding.toolbar,
                store = components.store,
                loadUrlUseCase = components.sessionUseCases.loadUrl,
                searchUseCase = null,
                customTabId = null,
                urlRenderConfiguration = null,
            ),
            owner = this,
            view = binding.root,
        )

        findInPageFeature.set(
            feature = FindInPageFeature(
                store = components.store,
                view = binding.findInPageBar,
                engineView = binding.engineView,
                // The bar's "X" → unbind() → we hide the bar in the dismiss callback below.
            ) { binding.findInPageBar.visibility = View.GONE },
            owner = this,
            view = binding.root,
        )
    }

    private fun wireBottomBar() = with(binding) {
        btnForward.setOnClickListener { components.sessionUseCases.goForward() }

        // Slot 2 — uBO quick toggle. The killer-feature switch lives here so the
        // user can flip it without opening the menu. The icon flip is the only
        // affordance; we deliberately don't show a toast on tap (Banana doesn't).
        btnAdblockShield.setOnClickListener {
            lifecycleScope.launch {
                runCatching { adblock.toggle() }
                renderAdblockShield()
            }
        }

        // Phase-3 stubs. Wired now so the buttons are tappable instead of dead;
        // the toast doubles as a roadmap hint to the user.
        btnBookmarks.setOnClickListener { stubToast() }
        btnReader.setOnClickListener { stubToast() }

        btnTabs.setOnClickListener { TabsTrayFragment().show(supportFragmentManager, "tabs") }
        btnMenu.setOnClickListener {
            // Banana-style anchored drop-down. We construct a fresh popup per
            // tap rather than caching one — avoids stale toggle states and
            // simplifies the lifecycle (no leak risk on activity recreate).
            AppMenuPopup(this@MainActivity).showFrom(it)
        }

        renderAdblockShield()
    }

    /** "Coming soon" feedback for the not-yet-implemented bookmarks/reader slots. */
    private fun stubToast() {
        Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
    }

    /**
     * Sync the shield button's icon + tint with the current uBO state. We run
     * the lookup in a coroutine because [AdblockController.isEnabled] has to
     * await the engine's `listInstalledWebExtensions` callback — calling it
     * synchronously races with the engine and would always read "OFF" on
     * cold start, even when uBO is installed and active.
     *
     * Trigger points: wireBottomBar() (initial), after our own tap on the
     * shield, after AppMenuPopup toggles uBO, and onResume(). Deliberately
     * NOT called per store tick — that storms the engine and ANRs the UI.
     */
    internal fun renderAdblockShield() {
        lifecycleScope.launch {
            val on = adblock.isEnabled()
            binding.btnAdblockShield.setImageResource(
                if (on) R.drawable.ic_shield else R.drawable.ic_shield_off
            )
            val tint = if (on) {
                com.google.android.material.color.MaterialColors.getColor(
                    binding.btnAdblockShield, androidx.appcompat.R.attr.colorPrimary
                )
            } else {
                com.google.android.material.color.MaterialColors.getColor(
                    binding.btnAdblockShield, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            }
            binding.btnAdblockShield.setColorFilter(tint)
        }
    }

    private fun wirePipFab() {
        binding.pipFab.setOnClickListener { enterPipMode() }
    }

    private fun wireBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Order matters: find-in-page first (it captures the X / soft-back),
                // then toolbar edit-mode → page goBack, finally tab close → finish().
                val handled = (findInPageFeature.get() as? UserInteractionHandler)?.onBackPressed() == true
                    || (toolbarFeature.get() as? UserInteractionHandler)?.onBackPressed() == true
                    || (sessionFeature.get() as? UserInteractionHandler)?.onBackPressed() == true
                if (handled) return

                val state = components.store.state
                if (state.tabs.size > 1) {
                    state.selectedTabId?.let { components.tabsUseCases.removeTab(it) }
                } else {
                    finish()
                }
            }
        })
    }

    // --- Store observation -------------------------------------------------

    private fun observeStore() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                components.store.flow().collect { state ->
                    val tab = state.selectedTab
                    binding.tabCount.text = state.tabs.size.toString()

                    // Forward only — back is the system gesture (slot removed).
                    val canForward = tab?.content?.canGoForward == true
                    binding.btnForward.isEnabled = canForward
                    binding.btnForward.alpha = if (canForward) 1f else 0.35f

                    // NOTE: do NOT call renderAdblockShield() here. The shield's
                    // state is read async via the engine; firing one async lookup
                    // per store tick (dozens during a page load) backs up the
                    // engine queue and ANRs the main thread. Shield is refreshed
                    // explicitly: at wireBottomBar(), after our own tap, and on
                    // each onResume() (covers menu-popup toggles).

                    // Speed-dial overlay vs engine view. Empty/blank URL == start page.
                    val url = tab?.content?.url.orEmpty()
                    val isHome = url.isBlank() || url == HOME_URL
                    startPage.setVisible(isHome)

                    // Floating PiP button: visible only while media is actively
                    // playing on the selected tab. PAUSED hides it because the
                    // user can resume from the page itself.
                    val isPlaying = tab?.mediaSessionState?.playbackState ==
                        MediaSession.PlaybackState.PLAYING
                    binding.pipFab.isVisible = isPlaying && !isInPipMode()
                }
            }
        }
    }

    // --- Speed-dial --------------------------------------------------------

    private fun onQuickLinkClick(link: QuickLink) {
        components.sessionUseCases.loadUrl(link.url)
        // Start page hides automatically when tab.content.url changes.
    }

    fun showFindInPage() {
        val tab = components.store.state.selectedTab ?: return
        binding.findInPageBar.visibility = View.VISIBLE
        findInPageFeature.get()?.bind(tab)
    }

    // --- Picture-in-Picture ------------------------------------------------

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    private fun isInPipMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /**
     * In PiP we only want the engine to be visible — the system shrinks the
     * activity to a small floating window and any chrome (toolbar, bottom bar)
     * just steals pixels from the actual video. Toggle visibility on transition
     * and let the standard observer decide on PiP-FAB visibility.
     */
    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        val v = if (isInPip) View.GONE else View.VISIBLE
        binding.toolbarWrapper.visibility = v
        binding.toolbarDivider.visibility = v
        binding.bottomDivider.visibility = v
        binding.bottomBar.visibility = v
        // Hide our own button while we're already in PiP — re-shown by the
        // store observer when we exit (if media still playing).
        if (isInPip) binding.pipFab.visibility = View.GONE
    }

    /**
     * If a video is playing when the user navigates home, auto-enter PiP so
     * playback continues over their next app. Cheap UX win — Chrome / Firefox
     * Mobile both do this.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val tab = components.store.state.selectedTab
        val isPlaying = tab?.mediaSessionState?.playbackState ==
            MediaSession.PlaybackState.PLAYING
        if (isPlaying && !isInPipMode()) enterPipMode()
    }

    // --- Helpers -----------------------------------------------------------

    private fun ensureAtLeastOneTab(initialUrl: String) {
        if (components.store.state.tabs.isEmpty()) {
            components.tabsUseCases.addTab(url = normalizeToUrl(initialUrl), selectTab = true)
        }
    }

    private fun Intent.dataStringIfView(): String? =
        if (action == Intent.ACTION_VIEW) dataString else null

    /** Bare URL or hostname → https; everything else → DuckDuckGo search. */
    private fun normalizeToUrl(input: String): String {
        if (input.isEmpty() || input == HOME_URL) return HOME_URL
        val parsed = Uri.parse(input)
        if (parsed.scheme != null) return input
        val looksLikeHost = !input.contains(' ') && input.contains('.')
        return if (looksLikeHost) "https://$input"
        else "https://duckduckgo.com/?q=" + Uri.encode(input)
    }

    companion object {
        /**
         * Sentinel "home" URL. We let the engine sit on about:blank and overlay
         * the speed-dial start page on top of the engine view; the urlFormatter
         * in [wireToolbar] renders this as an empty string in the omnibar.
         */
        const val HOME_URL = "about:blank"
    }
}
