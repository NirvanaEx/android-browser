package com.upgrid.browser

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.upgrid.browser.databinding.ActivityMainBinding
import com.upgrid.browser.fullscreen.PlayerOverlayController
import com.upgrid.browser.home.QuickLink
import com.upgrid.browser.home.StartPagePresenter
import com.upgrid.browser.menu.AppMenuPopup
import com.upgrid.browser.prefs.BrowserPreferences
import com.upgrid.browser.search.SearchHistory
import com.upgrid.browser.tabs.TabsTrayFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.findinpage.FindInPageFeature
import mozilla.components.feature.session.FullScreenFeature
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

    /** App-wide preferences (search engine, etc.). */
    private val preferences by lazy { BrowserPreferences(this) }

    /** Recent omnibar queries. Recorded in the URL-commit listener. */
    private val searchHistory by lazy { SearchHistory(this) }

    /**
     * True while the activity is in immersive "video focus" mode — chrome +
     * system bars hidden so the video fills the screen. Driven by
     * FullScreenFeature (engine fullscreen) and by the player takeover
     * fallback when requestFullscreen was rejected. wireBackPress consumes
     * back presses to exit this mode before default handling.
     */
    private var inVideoFocus = false

    /** Built-in player overlay: buttons, seek bar, gesture layer. */
    private lateinit var playerOverlay: PlayerOverlayController

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val toolbarFeature = ViewBoundFeatureWrapper<ToolbarFeature>()
    private val findInPageFeature = ViewBoundFeatureWrapper<FindInPageFeature>()
    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureAtLeastOneTab(initialUrl = intent.dataStringIfView() ?: HOME_URL)

        startPage = StartPagePresenter(binding.startPage) { link -> onQuickLinkClick(link) }
        wireToolbar()
        wireFeatures()
        wireDismissKeyboardOnEngineTap()
        wireBottomBar()
        wirePlayerOverlay()
        wireBackPress()
        observeStore()
    }

    override fun onStart() {
        super.onStart()
        // ToolbarFeature.start() runs on lifecycle ON_START via ViewBoundFeatureWrapper
        // and registers its own onUrlCommitListener — which would override ours
        // if we set it in onCreate. Re-set after super.onStart() so we win the
        // single-listener slot.
        wireUrlCommit()
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

        // Topbar menu trigger — anchored AppMenuPopup that grows downward from
        // the menu icon (auto-positioned by showAsDropDown).
        binding.btnTopMenu.setOnClickListener {
            AppMenuPopup(this@MainActivity).showFrom(it)
        }

        // Topbar video button → take over the page's video with the built-in
        // player. Fires the bundled extension's browser_action; Mozilla
        // preserves the user-gesture token from this Android click through to
        // the extension's executeScript, which strips the page player's
        // controls and calls <video>.requestFullscreen() with a valid
        // gesture. The takeover/state events come back through
        // VideoPlayerBridge.onPlayerEvent (see wirePlayerOverlay).
        //
        // Do NOT also call setVideoFocus(true) here: FullScreenFeature owns
        // the chrome state when fullscreen actually engages, and we don't
        // want to hide chrome optimistically before knowing whether the
        // request succeeded (silently misleading if Gecko rejects it).
        binding.btnTopVideo.setOnClickListener {
            components.videoPlayerBridge.requestTakeover()
        }
    }

    /**
     * Replace ToolbarFeature's URL-commit handler with our own so we can:
     *   1. Record free-text searches in history (real URLs are skipped).
     *   2. Resolve queries against the user's selected SearchEngine.
     *
     * Called from [onStart] — see that method for the timing rationale.
     */
    private fun wireUrlCommit() {
        binding.toolbar.setOnUrlCommitListener { input ->
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return@setOnUrlCommitListener true

            // Search history captures only free-text queries — URLs are noise.
            if (looksLikeQuery(trimmed)) searchHistory.record(trimmed)

            components.sessionUseCases.loadUrl(normalizeToUrl(trimmed))
            // Drop the keyboard + return the toolbar to display mode so the
            // user immediately sees the loading page instead of staring at
            // the IME. Without this, BrowserToolbar leaves the EditText
            // focused, the IME stays up, and the page looks "stuck".
            binding.toolbar.displayMode()
            hideKeyboard()
            true
        }
    }

    /**
     * Add a tap-to-dismiss interceptor on the engine view so a casual tap on
     * the page content closes the IME if it was raised by URL-bar focus.
     * Returns false from the listener so the engine still receives the touch
     * event for normal page interaction (link taps, scrolling, ...).
     */
    private fun wireDismissKeyboardOnEngineTap() {
        binding.engineView.asView().setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && binding.toolbar.hasFocus()) {
                binding.toolbar.displayMode()
                hideKeyboard()
            }
            false
        }
    }

    /** Hide soft keyboard if it's currently up. Cheap no-op otherwise. */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
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

        // Page-initiated fullscreen (engine fired onFullScreen). Chrome hides
        // either way; the overlay only shows when OUR takeover initiated the
        // fullscreen — that signal arrives separately through the bridge's
        // "takeover" event (see wirePlayerOverlay). A page going fullscreen
        // by itself keeps its own controls and we stay out of the way.
        fullScreenFeature.set(
            feature = FullScreenFeature(
                store = components.store,
                sessionUseCases = components.sessionUseCases,
                tabId = null,
                viewportFitChanged = { /* notch handling not needed for MVP */ },
                fullScreenChanged = { fs ->
                    setVideoFocus(fs)
                    if (!fs) playerOverlay.setVisible(false)
                },
            ),
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

    private fun wireBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. If the engine is in fullscreen, ask it to exit.
                //    FullScreenFeature fires fullScreenChanged(false) →
                //    chrome restores + overlay hides; the content script's
                //    own fullscreenchange listener releases the video back
                //    to the page and reports "released".
                if (fullScreenFeature.get()?.onBackPressed() == true) return

                // 2. Player overlay without engine fullscreen (the
                //    requestFullscreen fallback path) or stale video-focus →
                //    release the video and restore chrome ourselves.
                if (playerOverlay.isVisible || inVideoFocus) {
                    exitPlayer()
                    return
                }

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

    // --- Video focus (replaces PiP for the topbar play button) -------------

    /**
     * Single-state setter that hides/shows chrome + system bars. Driven from
     * two callers that must agree: the topbar video button (optimistic enter)
     * and FullScreenFeature (engine-driven enter/exit). Idempotent.
     *
     * When `focused`: hide chrome and put the system bars in sticky-immersive
     * (swipe from edge briefly reveals them). When `!focused`: restore.
     */
    private fun setVideoFocus(focused: Boolean) {
        if (focused == inVideoFocus) return
        inVideoFocus = focused
        val v = if (focused) View.GONE else View.VISIBLE
        binding.toolbarWrapper.visibility = v
        binding.toolbarDivider.visibility = v
        binding.bottomDivider.visibility = v
        binding.bottomBar.visibility = v

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (focused) {
            // fitsSystemWindows on the root applies the system/IME insets as
            // padding. Hiding the bars re-dispatches zero insets — usually.
            // On MIUI the re-dispatch can lag or be skipped entirely, which
            // leaves the root padded and the overlay's bottom-pinned controls
            // floating mid-screen. Clear the padding explicitly; restore by
            // re-enabling the flag and requesting a fresh insets pass.
            binding.root.fitsSystemWindows = false
            binding.root.setPadding(0, 0, 0, 0)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            binding.root.fitsSystemWindows = true
            controller.show(WindowInsetsCompat.Type.systemBars())
            binding.root.requestApplyInsets()
        }
    }

    // --- Built-in player overlay -------------------------------------------

    /**
     * Construct the overlay controller and subscribe to player events from
     * the bridge. The controller owns all in-overlay interaction (buttons,
     * seek bar, double-tap seek, volume/brightness drags); this activity only
     * decides outer visibility and chrome state.
     */
    private fun wirePlayerOverlay() {
        playerOverlay = PlayerOverlayController(
            binding = binding.fsOverlay,
            bridge = components.videoPlayerBridge,
            prefs = preferences,
            window = window,
            audioManager = getSystemService(AUDIO_SERVICE) as AudioManager,
            onExit = { exitPlayer() },
            onPip = { enterPipMode() },
            onRotate = { toggleFsOrientation() },
        )

        components.videoPlayerBridge.onPlayerEvent = { event ->
            when (event.optString("t")) {
                "takeover" -> if (event.optBoolean("ok")) {
                    playerOverlay.setVisible(true)
                    playerOverlay.renderState(event)
                    // Hide chrome unconditionally. Even when requestFullscreen
                    // is silently rejected (the content script can't know —
                    // the promise settles later), the takeover styles the
                    // video to fill the viewport, so chrome-less + overlay is
                    // the correct presentation either way. If engine
                    // fullscreen does engage, FullScreenFeature re-runs this
                    // idempotently.
                    setVideoFocus(true)
                } else {
                    Toast.makeText(this, R.string.player_no_video, Toast.LENGTH_SHORT).show()
                }
                "state" -> playerOverlay.renderState(event)
                "released" -> {
                    playerOverlay.setVisible(false)
                    setVideoFocus(false)
                }
            }
        }
    }

    /**
     * Exit the built-in player: give the video back to the page (restores its
     * controls + exits HTML5 fullscreen) and restore our chrome. Chrome is
     * restored immediately rather than waiting for the "released" round-trip;
     * the event re-runs the same idempotent calls a beat later.
     */
    private fun exitPlayer() {
        components.videoPlayerBridge.sendCommand("release")
        playerOverlay.setVisible(false)
        setVideoFocus(false)
    }

    private fun toggleFsOrientation() {
        requestedOrientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
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

                    // Topbar player button: visible whenever the selected tab
                    // has a media session (playing OR paused) — a paused video
                    // is still a valid takeover target for the built-in player.
                    val hasMedia = tab?.mediaSessionState != null
                    binding.btnTopVideo.isVisible = hasMedia && !isInPipMode()
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
        // The topbar video button hides with the rest of the topbar via the
        // wrapper visibility above; observer re-resolves the button visibility
        // on exit based on current media state.
    }

    // Auto-PiP-on-leave intentionally removed — the user prefers video to
    // remain in the page (with our video-focus mode for fullscreen) rather
    // than spawn a system PiP window when they background the app.

    // --- Helpers -----------------------------------------------------------

    private fun ensureAtLeastOneTab(initialUrl: String) {
        if (components.store.state.tabs.isEmpty()) {
            components.tabsUseCases.addTab(url = normalizeToUrl(initialUrl), selectTab = true)
        }
    }

    private fun Intent.dataStringIfView(): String? =
        if (action == Intent.ACTION_VIEW) dataString else null

    /**
     * Bare URL or hostname → https; everything else → search via the user's
     * configured engine ([BrowserPreferences.searchEngine], default Yandex).
     */
    private fun normalizeToUrl(input: String): String {
        if (input.isEmpty() || input == HOME_URL) return HOME_URL
        val parsed = Uri.parse(input)
        if (parsed.scheme != null) return input
        val looksLikeHost = !input.contains(' ') && input.contains('.')
        return if (looksLikeHost) "https://$input"
        else preferences.searchEngine.searchUrlFor(input)
    }

    /** True iff the trimmed input is a free-text query (not a URL or bare host). */
    private fun looksLikeQuery(input: String): Boolean {
        if (Uri.parse(input).scheme != null) return false
        // "github.com" looks like a host (no spaces, has dot); anything else
        // is a search query for our purposes.
        return input.contains(' ') || !input.contains('.')
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
