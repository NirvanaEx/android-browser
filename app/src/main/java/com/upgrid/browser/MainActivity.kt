package com.upgrid.browser

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import com.upgrid.browser.bookmarks.BookmarkStore
import com.upgrid.browser.bookmarks.BookmarksActivity
import com.upgrid.browser.databinding.ActivityMainBinding
import com.upgrid.browser.fullscreen.PlayerOverlayController
import com.upgrid.browser.history.HistoryActivity
import com.upgrid.browser.home.QuickLink
import com.upgrid.browser.home.StartPagePresenter
import com.upgrid.browser.menu.AppMenuPopup
import com.upgrid.browser.prefs.BrowserPreferences
import com.upgrid.browser.search.Suggestion
import com.upgrid.browser.search.SuggestionAdapter
import com.upgrid.browser.search.SuggestionSource
import com.upgrid.browser.sync.GoogleAccounts
import com.upgrid.browser.sync.SignInDiagnostics
import com.upgrid.browser.sync.SyncEngine
import com.upgrid.browser.tabs.TabsActivity
import com.upgrid.browser.history.HistoryStore
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.concept.toolbar.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.toolbar.display.DisplayToolbar
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
 * One observer on the store flow drives the rest of the chrome: the tab
 * counter, the start-page-vs-engine swap, the player button's visibility, and
 * the history write for the page that just settled.
 *
 * All chrome lives in the single top bar; there is no bottom bar (see the note
 * at the top of activity_main.xml for where its contents went). Everything
 * else the browser offers is behind the ⋮ menu, the tabs tray, or Settings.
 *
 * If the store has zero tabs at startup we open one to [HOME_URL]; on cold
 * start [BrowserApplication] tries to restore the previous session first.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var startPage: StartPagePresenter
    private val components get() = (application as BrowserApplication).components

    /** App-wide preferences (search engine, etc.). */
    private val preferences by lazy { BrowserPreferences(this) }

    // The three data stores live in BrowserComponents, one instance per
    // process. Constructing them per screen meant a separate SQLite connection
    // to the same file from every activity that showed a list.
    private val searchHistory get() = components.searchHistory
    private val browsingHistory get() = components.browsingHistory
    private val bookmarks get() = components.bookmarks

    /** Omnibar drop-down: bookmarks, then visited pages, then past searches. */
    private val suggestionSource by lazy { SuggestionSource(components) }
    private val suggestionAdapter by lazy { SuggestionAdapter(::onSuggestionPicked) }
    private var suggestionJob: Job? = null

    /**
     * The bookmark star inside the URL chip. Held so the store observer can
     * flip it as the page changes; [Toolbar.ActionToggleButton] renders from
     * its own state and only redraws on `invalidateActions`.
     */
    private var bookmarkAction: Toolbar.ActionToggleButton? = null

    /** URL whose bookmarked state [bookmarkAction] currently reflects. */
    private var bookmarkedUrl: String? = null

    /**
     * Google sign-in, launched from the app menu as well as from Settings.
     * Registered here because a PopupWindow has nowhere to register one, and
     * activity-result contracts must be registered before STARTED.
     */
    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            GoogleAccounts.accountFromResult(result.data)
                .onSuccess {
                    Toast.makeText(this, R.string.settings_account_connected, Toast.LENGTH_SHORT)
                        .show()
                    lifecycleScope.launch {
                        runCatching { SyncEngine(applicationContext).sync() }
                        refreshStartPage()
                    }
                }
                .onFailure(::showSignInFailure)
        }

    /**
     * Last (url, title) written to history, per tab id. The store ticks many
     * times per page load with identical content; without a memo the same page
     * is re-recorded on every progress update, inflating its visit counter and
     * churning the DB.
     *
     * Keyed by tab rather than held as a single slot so that switching A → B →
     * A doesn't read as a fresh visit to A. Bounded by the open tab count.
     */
    private val lastRecordedVisit = mutableMapOf<String, Pair<String, String>>()

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

    /**
     * True from the moment we ask the content script to drop DOM fullscreen
     * for PiP until the PiP transition settles.
     *
     * That exit travels back through the engine as a perfectly ordinary "page
     * left fullscreen", so [FullScreenFeature] would clear the video focus and
     * hide the overlay right as we're moving into the floating window — and
     * the content script's own watcher can report a release if it loses the
     * race with the activity resize. Both are our own doing, not the user's,
     * and both are ignored while this is set.
     */
    private var pipTransition = false

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

        startPage = StartPagePresenter(
            binding = binding.startPage,
            icons = components.icons,
            scope = lifecycleScope,
            onLinkClick = { link -> components.sessionUseCases.loadUrl(link.url) },
            onLinkLongClick = { link -> confirmRemoveQuickLink(link) },
            onAddClick = { promptAddQuickLink() },
        )
        wireToolbar()
        wireSuggestions()
        wireFeatures()
        wireDismissKeyboardOnEngineTap()
        wirePlayerOverlay()
        wireBackPress()
        observeStore()
        refreshStartPage()

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
        // The bridge lives in BrowserComponents, i.e. for the whole process —
        // a stale lambda here keeps this activity (and its whole view tree)
        // reachable until some later instance overwrites it. Recreation is not
        // hypothetical: `configChanges` doesn't list uiMode, so flipping system
        // dark mode rebuilds the activity.
        components.videoPlayerBridge.onPlayerEvent = {}
    }

    override fun onStart() {
        super.onStart()
        // ToolbarFeature.start() runs on lifecycle ON_START via ViewBoundFeatureWrapper
        // and registers its own onUrlCommitListener — which would override ours
        // if we set it in onCreate. Re-set after super.onStart() so we win the
        // single-listener slot.
        wireUrlCommit()
        wireEditListener()
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
        if (!::binding.isInitialized) return
        // Bookmarks can change from anywhere (menu star, bookmarks sheet, a
        // sync that pulled in another device's), and the speed dial is drawn
        // from them.
        refreshStartPage()
        maybeAutoSync()
    }

    override fun onPause() {
        super.onPause()
        // Last unambiguous moment to photograph the current tab: it is still
        // the rendered one, and whatever comes next (another activity, the
        // launcher) will cover it.
        if (::binding.isInitialized) captureCurrentThumbnail()
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

        // The tab grid needs a preview of the page we're leaving; capture is
        // only possible while it's still the rendered one.
        binding.btnTopTabs.setOnClickListener {
            captureCurrentThumbnail()
            startActivity(TabsActivity.intent(this))
        }

        addBookmarkAction()

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
     * The bookmark star, as a page action inside the URL chip.
     *
     * A page action rather than a sixth button in the bar: it belongs to the
     * address it acts on, and the bar has no room left — four 44dp targets plus
     * the video button already leave the URL under half the screen on a phone.
     *
     * [Toolbar.ActionToggleButton] owns its own selected state and only redraws
     * on `invalidateActions`, so the store observer drives it through
     * [renderBookmarkAction] rather than by rebuilding the action.
     */
    private fun addBookmarkAction() {
        val outline = ContextCompat.getDrawable(this, R.drawable.ic_bookmark) ?: return
        val filled = ContextCompat.getDrawable(this, R.drawable.ic_bookmark_filled) ?: return

        val action = Toolbar.ActionToggleButton(
            imageDrawable = outline,
            imageSelectedDrawable = filled,
            contentDescription = getString(R.string.menu_bookmark_add),
            contentDescriptionSelected = getString(R.string.menu_bookmark_remove),
            // Chrome URLs and the start page can't be saved; showing a star
            // that answers with an error toast is worse than showing none.
            visible = { BookmarkStore.isBookmarkable(currentUrl()) },
            background = R.drawable.bg_toolbar_action,
        ) { toggleBookmarkForCurrentPage() }

        bookmarkAction = action
        binding.toolbar.addPageAction(action)
    }

    private fun currentUrl(): String =
        components.store.state.selectedTab?.content?.url.orEmpty()

    private fun toggleBookmarkForCurrentPage() {
        val tab = components.store.state.selectedTab ?: return
        lifecycleScope.launch {
            val saved = bookmarks.toggle(tab.content.url, tab.content.title)
            // ActionToggleButton already flipped itself on tap; only correct it
            // if the store disagreed (an unbookmarkable URL slipping through).
            bookmarkAction?.setSelected(saved, notifyListener = false)
            bookmarkedUrl = tab.content.url
            Toast.makeText(
                this@MainActivity,
                if (saved) R.string.bookmark_added else R.string.bookmark_removed,
                Toast.LENGTH_SHORT,
            ).show()
            refreshStartPage()
        }
    }

    /**
     * Sync the star with the page on screen.
     *
     * Called from the store observer, so it has to be cheap: [bookmarkedUrl]
     * memoises the last URL looked up, because the observer fires many times
     * per page load and each miss is a database round-trip.
     */
    private fun renderBookmarkAction(url: String) {
        if (url == bookmarkedUrl) return
        bookmarkedUrl = url
        val action = bookmarkAction ?: return
        if (!BookmarkStore.isBookmarkable(url)) {
            action.setSelected(false, notifyListener = false)
            binding.toolbar.invalidateActions()
            return
        }
        lifecycleScope.launch {
            val saved = bookmarks.isBookmarked(url)
            // The page may have moved on while the query was in flight.
            if (bookmarkedUrl != url) return@launch
            action.setSelected(saved, notifyListener = false)
            binding.toolbar.invalidateActions()
        }
    }

    // --- Omnibar suggestions ------------------------------------------------

    private fun wireSuggestions() {
        binding.suggestionsList.layoutManager = LinearLayoutManager(this)
        binding.suggestionsList.adapter = suggestionAdapter
    }

    /**
     * Feed the drop-down while the URL bar is being edited.
     *
     * Set from [onStart] alongside the commit listener: `ToolbarFeature.start()`
     * runs on ON_START and installs its own listeners, and both of these are
     * single-slot.
     */
    private fun wireEditListener() {
        binding.toolbar.setOnEditListener(object : Toolbar.OnEditListener {
            override fun onStartEditing() {
                // Nothing to suggest for an empty field; wait for a keystroke.
            }

            override fun onStopEditing() = hideSuggestions()

            override fun onCancelEditing(): Boolean {
                hideSuggestions()
                return true
            }

            override fun onInputCleared() = hideSuggestions()

            override fun onTextChanged(text: String) {
                suggestionJob?.cancel()
                suggestionJob = lifecycleScope.launch {
                    // Same 250 ms as the history filter: one query per word
                    // typed rather than one per letter, still reads as live.
                    delay(SUGGESTION_DEBOUNCE_MS)

                    // Two passes on purpose. What we already know — bookmarks,
                    // history, past searches — is three local reads and can be
                    // on screen straight away; the engine's completions take a
                    // network round-trip and land a moment later. Rendering
                    // both at once would mean the whole drop-down waits on the
                    // slowest half, which on a bad connection is "never".
                    val local = suggestionSource.local(text)
                    showSuggestions(local)
                    showSuggestions(suggestionSource.withRemote(text, local))
                }
            }
        })
    }

    private fun showSuggestions(items: List<Suggestion>) {
        suggestionAdapter.submit(items)
        binding.suggestionsList.isVisible = items.isNotEmpty()
    }

    private fun hideSuggestions() {
        suggestionJob?.cancel()
        binding.suggestionsList.isVisible = false
    }

    private fun onSuggestionPicked(suggestion: Suggestion) {
        hideSuggestions()
        binding.toolbar.displayMode()
        hideKeyboard()
        when (suggestion.kind) {
            // A query is text, not a destination: run it through the user's
            // engine rather than trying to load it as a URL. Recorded too —
            // picking a completion is as much a search as typing one, and
            // otherwise the past-searches half of this list never fills up.
            Suggestion.Kind.SEARCH -> {
                searchHistory.record(suggestion.target)
                components.sessionUseCases.loadUrl(normalizeToUrl(suggestion.target))
            }
            else -> components.sessionUseCases.loadUrl(suggestion.target)
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
                    // Entering PiP means asking the page to leave fullscreen,
                    // and the engine reports that here as if the page gave up
                    // on its own. Acting on it would restore the chrome under
                    // the overlay the moment we come back from the floating
                    // window. Only a real exit — one we didn't cause — counts.
                    if (fs || !(pipTransition || isInPipMode())) {
                        setVideoFocus(fs)
                        if (!fs) playerOverlay.setVisible(false)
                    }
                },
            ),
            owner = this,
            view = binding.root,
        )
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
                // A release landing mid-PiP-handoff is the content script's
                // fullscreen watcher losing a race with the activity resize,
                // not the page actually taking its video back. Once we're in
                // PiP the script's own pipMode guard holds, so a release from
                // there (navigation, tab close) is genuine and honoured.
                "released" -> if (!pipTransition) {
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
                // Two collectors over one flow, on purpose.
                //
                // The store ticks dozens of times during a single page load —
                // every progress update is a new state. Almost none of those
                // change the chrome, so mapping to just the fields the chrome
                // renders and running them through distinctUntilChanged turns
                // a hundred redundant view writes per load into three or four.
                //
                // History can't share that filter: it needs the title, which
                // arrives on a tick where nothing else changed.
                launch {
                    components.store.flow()
                        .map { state ->
                            val tab = state.selectedTab
                            ChromeState(
                                tabCount = state.tabs.size,
                                selectedTabId = state.selectedTabId,
                                url = tab?.content?.url.orEmpty(),
                            )
                        }
                        .distinctUntilChanged()
                        .collect(::renderChrome)
                }

                launch {
                    components.store.flow().collect { recordVisit(it.selectedTab) }
                }
            }
        }
    }

    /** The slice of store state the top bar and the start page actually render. */
    private data class ChromeState(
        val tabCount: Int,
        val selectedTabId: String?,
        val url: String,
    )

    private fun renderChrome(state: ChromeState) {
        binding.tabCount.text = state.tabCount.toString()

        // NOTE: nothing here may read uBO's state. That lookup is async through
        // the engine, and firing one per store tick backs the engine queue up
        // and ANRs the main thread. The AdBlock switch is rendered when the
        // menu or the settings sheet opens — never on a tick.

        // Speed-dial overlay vs engine view. Empty/blank URL == start page.
        val isHome = state.url.isBlank() || state.url == HOME_URL
        startPage.setVisible(isHome)
        renderSecurityIndicator(isHome)

        // Topbar player button. This used to be gated on tab.mediaSessionState,
        // but that's only populated for pages that opt into the MediaSession
        // API — plenty of sites with a plain <video> never set it, so the button
        // silently never appeared and the whole feature read as broken. Show it
        // on any real page instead; tapping with nothing to grab answers with
        // the "no video" toast, which is far better feedback than an invisible
        // control.
        binding.btnTopVideo.isVisible = !isHome && !isInPipMode()

        renderBookmarkAction(state.url)

        // selectedTabId is in ChromeState only so this filter can tell two tabs
        // showing the same URL apart. Capturing a preview here would be wrong:
        // by the time a selection change reaches us the engine is already
        // rendering the new tab, so the shot would be the old page's pixels
        // filed under the new tab's id. Previews are taken at the two moments
        // where what's on screen is unambiguous — onPause, and the tap that
        // opens the grid.
    }

    /**
     * Hide the padlock/shield badge on the start page.
     *
     * DisplayToolbar drops it by itself when the URL is empty, but our URL
     * isn't empty — it's `about:blank`, which the toolbar has no reason to know
     * is "home". The result was a "not secure" warning sitting next to an empty
     * search field on a page that isn't a page. The indicator list is the
     * public lever for that.
     */
    private fun renderSecurityIndicator(isHome: Boolean) {
        val wanted = if (isHome) {
            emptyList()
        } else {
            listOf(DisplayToolbar.Indicators.SECURITY)
        }
        // Assigning re-runs the toolbar's visibility pass, and this is called on
        // every store tick.
        if (binding.toolbar.display.indicators != wanted) {
            binding.toolbar.display.indicators = wanted
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

        val previous = lastRecordedVisit[tab.id]
        if (previous?.first == url) {
            if (title.isBlank() || title == previous.second) return
            lastRecordedVisit[tab.id] = url to title
            lifecycleScope.launch { browsingHistory.updateTitle(url, title) }
            return
        }

        lastRecordedVisit[tab.id] = url to title
        lifecycleScope.launch { browsingHistory.record(url, title) }
    }

    /** Browsing history. Opened from the app menu and from settings. */
    fun showHistory() = startActivity(HistoryActivity.intent(this))

    /** Bookmarks. Opened from the app menu and from settings. */
    fun showBookmarks() = startActivity(BookmarksActivity.intent(this))

    /** Google sign-in, callable from the app menu (which can't own a launcher). */
    fun connectGoogleAccount() = signInLauncher.launch(GoogleAccounts.signInIntent(this))

    /**
     * Say what actually went wrong.
     *
     * The one failure worth a whole dialog is the missing OAuth client: nothing
     * the user can do in the app fixes it, and the fix elsewhere needs two
     * values only the installed APK knows. Everything else is a toast — with
     * the numeric code in it, because "error 7" can be looked up and "couldn't
     * connect" cannot.
     */
    private fun showSignInFailure(error: Throwable) {
        if (!SignInDiagnostics.isNotConfigured(error)) {
            Toast.makeText(this, SignInDiagnostics.describe(this, error), Toast.LENGTH_LONG).show()
            return
        }

        val details = SignInDiagnostics.oauthClientDetails(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_setup_title)
            .setMessage("${getString(R.string.account_setup_message)}\n\n$details")
            .setPositiveButton(R.string.account_setup_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), details))
                Toast.makeText(this, R.string.account_setup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Speed-dial --------------------------------------------------------

    /**
     * Redraw the speed dial from the user's bookmarks.
     *
     * Bookmarks come first, then defaults fill whatever slots are left — saving
     * one page shouldn't wipe the other seven tiles off a brand-new install.
     * `distinctBy(url)` keeps a bookmarked default (say, YouTube) from appearing
     * twice, and defaults the user removed stay removed.
     *
     * Public because the menu star, the bookmarks screen and a completed sync
     * all change the answer.
     */
    fun refreshStartPage() {
        lifecycleScope.launch {
            val hidden = preferences.hiddenQuickLinks
            val saved = bookmarks.all().map { QuickLink.of(it) }
            startPage.setLinks(
                (saved + QuickLink.SEED)
                    .filterNot { it.url in hidden }
                    .distinctBy { it.url }
                    .take(BookmarkStore.SPEED_DIAL_SLOTS)
            )
        }
    }

    /**
     * "+" tile → add a shortcut.
     *
     * A shortcut IS a bookmark: the speed dial is drawn from them, so a second
     * store for "tiles" would be the same rows under a different name, and the
     * two would drift apart the first time one was edited.
     */
    private fun promptAddQuickLink() {
        val input = EditText(this).apply {
            setHint(R.string.start_page_add_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            // Pre-fill with the page they're on, which is the common case.
            currentUrl().takeIf { BookmarkStore.isBookmarkable(it) }?.let { setText(it) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_page_add)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.start_page_add_confirm) { _, _ ->
                val url = normalizeToUrl(input.text?.toString()?.trim().orEmpty())
                if (!BookmarkStore.isBookmarkable(url)) {
                    Toast.makeText(this, R.string.bookmark_not_saveable, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    if (!bookmarks.isBookmarked(url)) bookmarks.toggle(url, "")
                    // Adding back a default the user had removed has to clear
                    // the tombstone, or the tile silently never reappears.
                    preferences.hiddenQuickLinks = preferences.hiddenQuickLinks - url
                    refreshStartPage()
                }
            }
            .show()
    }

    /**
     * Long-press a tile → remove it.
     *
     * Two different removals behind one gesture: a saved tile is a bookmark and
     * gets deleted, a default has nothing to delete and is instead remembered
     * as hidden.
     */
    private fun confirmRemoveQuickLink(link: QuickLink) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_page_remove_title)
            .setMessage(getString(R.string.start_page_remove_message, link.label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.start_page_remove_confirm) { _, _ ->
                lifecycleScope.launch {
                    if (link.saved) {
                        bookmarks.all().firstOrNull { it.url == link.url }
                            ?.let { bookmarks.delete(it.id) }
                    }
                    preferences.hideQuickLink(link.url)
                    refreshStartPage()
                }
            }
            .show()
    }

    // --- Tab previews ------------------------------------------------------

    /**
     * Snapshot the rendered tab for the tabs grid.
     *
     * `captureThumbnail` can only ever see the tab currently on screen, so this
     * runs just before leaving for the grid and whenever the selection changes.
     * Everything else keeps whatever it had when it was last visible.
     */
    private fun captureCurrentThumbnail() {
        val tabId = components.store.state.selectedTabId ?: return
        if (startPage.isVisible) return
        runCatching {
            binding.engineView.captureThumbnail { bitmap ->
                if (bitmap != null) components.tabThumbnails.put(tabId, bitmap)
            }
        }
    }

    // --- Sync --------------------------------------------------------------

    /**
     * Sync in the background on resume, at most every
     * [BrowserPreferences.AUTO_SYNC_INTERVAL_MS].
     *
     * Silent by design: no spinner, no toast, no error. A background sync that
     * interrupts the user to report a network hiccup is worse than one that
     * quietly tries again in half an hour — the Settings sheet is where a
     * failure is worth reading, because that's where the user went to ask.
     */
    private fun maybeAutoSync() {
        if (!preferences.autoSync) return
        if (GoogleAccounts.current(this) == null) return
        val since = System.currentTimeMillis() - preferences.lastSyncAt
        if (since < BrowserPreferences.AUTO_SYNC_INTERVAL_MS) return

        lifecycleScope.launch {
            runCatching { SyncEngine(applicationContext).sync() }
            // Another device's bookmarks may have landed.
            refreshStartPage()
        }
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

        pipTransition = true
        components.videoPlayerBridge.sendCommand("pip") { put("on", true) }
        val entered = runCatching { enterPictureInPictureMode(buildPipParams()) }
            .getOrDefault(false)
        if (!entered) {
            // Denied — per-app PiP permission off, or the system refused.
            // The content script has already left DOM fullscreen by now and
            // there's no gesture left to re-enter it, so settle back into our
            // own fullscreen presentation: the video is still styled to fill
            // the viewport, so chrome-off plus the overlay looks the same.
            pipTransition = false
            components.videoPlayerBridge.sendCommand("pip") { put("on", false) }
            playerOverlay.setVisible(true)
            setVideoFocus(true)
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
        // Handoff is over either way: from here isInPipMode() is authoritative.
        pipTransition = false

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

        /**
         * Debounce before querying the omnibar drop-down. Same 250 ms the
         * history filter uses: one query per word typed rather than one per
         * letter, still fast enough to read as live.
         */
        private const val SUGGESTION_DEBOUNCE_MS = 250L
    }
}
