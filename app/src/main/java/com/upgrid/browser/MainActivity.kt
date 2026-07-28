package com.upgrid.browser

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
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
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.upgrid.browser.databinding.ActivityMainBinding
import com.upgrid.browser.fullscreen.PlayerOverlayController
import com.upgrid.browser.history.HistoryFragment
import com.upgrid.browser.history.HistoryStore
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

    /** Visited pages. Written from the store observer once a load settles. */
    private val browsingHistory by lazy { HistoryStore(this) }

    /**
     * Last (url, title) pair actually written to history. The store ticks many
     * times per page load with the same content; without this the same page is
     * re-recorded on every progress update, inflating its visit counter and
     * churning the DB.
     */
    private var lastRecordedVisit: Pair<String, String>? = null

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

    /**
     * True between a successful takeover and the matching release. Distinct
     * from `playerOverlay.isVisible`, which goes false in PiP while the player
     * is very much still running — PiP transitions consult this to decide
     * whether to restore the overlay or tear the player down.
     */
    private var playerActive = false

    /** Latest playback state, mirrored from the content script's snapshots.
     *  Feeds the PiP window's aspect ratio and its play/pause action icon. */
    private var playerIsPlaying = false
    private var playerVideoWidth = 0
    private var playerVideoHeight = 0

    /**
     * Handles taps on the PiP window's action buttons. Registered
     * NOT_EXPORTED — these intents only ever come from our own PendingIntents.
     */
    private val playerControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bridge = components.videoPlayerBridge
            when (intent?.getStringExtra(EXTRA_CONTROL)) {
                CONTROL_TOGGLE -> bridge.sendCommand("toggle")
                CONTROL_REWIND -> bridge.sendCommand("seekBy") {
                    put("delta", -preferences.playerSeekSeconds)
                }
                CONTROL_FORWARD -> bridge.sendCommand("seekBy") {
                    put("delta", preferences.playerSeekSeconds)
                }
            }
        }
    }

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

        ContextCompat.registerReceiver(
            this,
            playerControlReceiver,
            IntentFilter(ACTION_PLAYER_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(playerControlReceiver) }
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
            // The extension announces its browser action a beat after install,
            // so a tap during the first seconds of a cold start has no gesture
            // path to travel. Say so rather than swallowing the tap.
            if (!components.videoPlayerBridge.requestTakeover()) {
                Toast.makeText(this, R.string.player_not_ready, Toast.LENGTH_SHORT).show()
            }
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
        applyVideoFocus(focused)
    }

    /**
     * Push the chrome + system-bar state onto the window WITHOUT touching
     * [inVideoFocus]. Entering and leaving PiP resizes the activity out from
     * under us, so the state has to be re-applied on each transition — but PiP
     * is not itself a change of video focus, and folding it into
     * [setVideoFocus] would make the idempotence guard swallow the re-apply.
     */
    private fun applyVideoFocus(focused: Boolean) {
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
                    playerActive = true
                    rememberPlayerState(event)
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
                "state" -> {
                    rememberPlayerState(event)
                    playerOverlay.renderState(event)
                }
                "released" -> {
                    playerActive = false
                    playerOverlay.setVisible(false)
                    setVideoFocus(false)
                }
            }
        }
    }

    /**
     * Mirror the content script's snapshot into the fields PiP needs.
     *
     * The snapshot arrives twice a second, but [setPictureInPictureParams] is
     * a binder call into the system server — only refresh the window when
     * something it renders actually changed, i.e. the play/pause icon flipped
     * or the video's intrinsic size finally resolved.
     */
    private fun rememberPlayerState(event: org.json.JSONObject) {
        val playing = !(event.optBoolean("paused", true) || event.optBoolean("ended", false))
        val width = event.optInt("vw", 0)
        val height = event.optInt("vh", 0)

        val changed = playing != playerIsPlaying ||
            width != playerVideoWidth ||
            height != playerVideoHeight

        playerIsPlaying = playing
        playerVideoWidth = width
        playerVideoHeight = height

        if (changed && isInPipMode() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { setPictureInPictureParams(buildPipParams()) }
        }
    }

    /**
     * Exit the built-in player: give the video back to the page (restores its
     * controls + exits HTML5 fullscreen) and restore our chrome. Chrome is
     * restored immediately rather than waiting for the "released" round-trip;
     * the event re-runs the same idempotent calls a beat later.
     */
    private fun exitPlayer() {
        playerActive = false
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

                    // Topbar player button. This used to be gated on
                    // tab.mediaSessionState, but that's only populated for
                    // pages that opt into the MediaSession API — plenty of
                    // sites with a plain <video> never set it, so the button
                    // silently never appeared and the whole feature read as
                    // broken. Show it on any real page instead; tapping with
                    // nothing to grab answers with the "no video" toast, which
                    // is far better feedback than an invisible control.
                    binding.btnTopVideo.isVisible = !isHome && !isInPipMode()

                    recordVisit(tab)
                }
            }
        }
    }

    /**
     * Write the selected tab's page into browsing history.
     *
     * Called on every store tick, so it has to be cheap and idempotent. Two
     * things make it so:
     *
     *  - We skip while the tab is still loading. The URL lands long before the
     *    <title> does, and recording early would fill history with untitled
     *    rows that only ever show a bare host.
     *  - Re-entering the same URL bumps the title but NOT the visit counter.
     *    Titles routinely arrive (or change, on SPAs) after the load settles,
     *    and counting those as fresh visits would inflate every row.
     */
    private fun recordVisit(tab: mozilla.components.browser.state.state.TabSessionState?) {
        val content = tab?.content ?: return
        if (content.loading) return

        val url = content.url
        val title = content.title
        if (url == HOME_URL || !HistoryStore.isRecordable(url)) return

        val previous = lastRecordedVisit
        if (previous?.first == url) {
            if (title.isBlank() || title == previous.second) return
            lastRecordedVisit = url to title
            lifecycleScope.launch { browsingHistory.updateTitle(url, title) }
            return
        }

        lastRecordedVisit = url to title
        lifecycleScope.launch { browsingHistory.record(url, title) }
    }

    /** Browsing history sheet. Opened from the app menu. */
    fun showHistory() {
        HistoryFragment().show(supportFragmentManager, HistoryFragment.TAG)
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

    /**
     * Move the running player into a floating system window.
     *
     * The order here is load-bearing. Android PiP and DOM fullscreen cannot
     * coexist: the system resizes the activity, Gecko drops fullscreen, and
     * the content script's `fullscreenchange` watcher reads that as "the user
     * left" and hands the video back to the page mid-transition — which is
     * what made the old PiP button produce a shrunken window showing the bare
     * page. So we tell the content script we're heading into PiP *first*; it
     * then leaves fullscreen on its own terms while keeping the takeover, and
     * the video keeps filling the viewport, which in PiP is the whole window.
     */
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, R.string.pip_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, R.string.pip_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        if (!playerActive) {
            Toast.makeText(this, R.string.pip_needs_player, Toast.LENGTH_SHORT).show()
            return
        }

        components.videoPlayerBridge.sendCommand("pip") { put("on", true) }
        val entered = runCatching { enterPictureInPictureMode(buildPipParams()) }
            .getOrDefault(false)
        if (!entered) {
            // Denied (per-app PiP permission off, or the system refused) —
            // undo the content-script side so we stay in normal fullscreen
            // instead of a fullscreen with no DOM fullscreen behind it.
            components.videoPlayerBridge.sendCommand("pip") { put("on", false) }
            Toast.makeText(this, R.string.pip_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio())
            .setActions(pipActions())
            .build()

    /**
     * Shape the PiP window to the video rather than assuming 16:9 — portrait
     * clips (Shorts, TikTok mirrors, VK clips) otherwise get letterboxed into
     * a landscape window with black bars down both sides.
     *
     * The system rejects anything outside roughly 1:2.39…2.39:1 with an
     * IllegalArgumentException, so out-of-range videos are clamped to the
     * nearest legal ratio instead of crashing the transition.
     */
    private fun pipAspectRatio(): Rational {
        val w = playerVideoWidth
        val h = playerVideoHeight
        if (w <= 0 || h <= 0) return Rational(16, 9)
        return when {
            w.toFloat() / h > MAX_PIP_ASPECT -> Rational(239, 100)
            h.toFloat() / w > MAX_PIP_ASPECT -> Rational(100, 239)
            else -> Rational(w, h)
        }
    }

    /**
     * Rewind / play-pause / forward buttons inside the PiP window. Without
     * these the floating window is a picture you can't control without
     * restoring the app first, which defeats the point.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(): List<RemoteAction> {
        val step = preferences.playerSeekSeconds
        return listOf(
            remoteAction(
                R.drawable.ic_seek_back,
                getString(R.string.player_seek_back, step),
                CONTROL_REWIND,
            ),
            if (playerIsPlaying) {
                remoteAction(R.drawable.ic_pause, getString(R.string.player_pause), CONTROL_TOGGLE)
            } else {
                remoteAction(
                    R.drawable.ic_play_filled,
                    getString(R.string.player_play),
                    CONTROL_TOGGLE,
                )
            },
            remoteAction(
                R.drawable.ic_seek_forward,
                getString(R.string.player_seek_forward, step),
                CONTROL_FORWARD,
            ),
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun remoteAction(iconRes: Int, title: String, control: String): RemoteAction {
        val intent = Intent(ACTION_PLAYER_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_CONTROL, control)
        val pending = PendingIntent.getBroadcast(
            this,
            control.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pending)
    }

    private fun isInPipMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /**
     * In PiP only the engine should be visible: the system shrinks us to a
     * thumbnail, where the chrome and the player overlay's 44dp buttons would
     * cover the video outright. The overlay in particular also swallows every
     * touch, so leaving it up made the floating window unresponsive.
     */
    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)

        if (isInPip) {
            applyVideoFocus(true)
            playerOverlay.setVisible(false)
            return
        }

        // Two ways out. Dismissing the window with its X stops the activity —
        // CREATED here means we're on our way to STOPPED, so there's no UI
        // worth restoring, and the video has to be handed back or it keeps
        // playing under a page nobody can see.
        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            exitPlayer()
            return
        }

        // Restored to full screen. DOM fullscreen is gone for good (PiP
        // dropped it, and re-requesting needs a user gesture we don't have),
        // but the content script still has the video filling the viewport —
        // so chrome-off plus the overlay is the same picture as before.
        components.videoPlayerBridge.sendCommand("pip") { put("on", false) }
        playerOverlay.setVisible(playerActive)
        applyVideoFocus(inVideoFocus)
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

        /**
         * Broadcast plumbing for the PiP window's action buttons. The intent
         * is package-scoped and the receiver is registered NOT_EXPORTED — only
         * our own PendingIntents ever reach it.
         */
        private const val ACTION_PLAYER_CONTROL = "com.upgrid.browser.PLAYER_CONTROL"
        private const val EXTRA_CONTROL = "control"
        private const val CONTROL_TOGGLE = "toggle"
        private const val CONTROL_REWIND = "rewind"
        private const val CONTROL_FORWARD = "forward"

        /** Widest window the system will hand out for PiP, either orientation. */
        private const val MAX_PIP_ASPECT = 2.39f
    }
}
