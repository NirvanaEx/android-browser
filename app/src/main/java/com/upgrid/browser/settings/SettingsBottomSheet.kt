package com.upgrid.browser.settings

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.AdblockController
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.BuildConfig
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.bookmarks.BookmarkStore
import com.upgrid.browser.databinding.FragmentSettingsBinding
import com.upgrid.browser.history.HistoryStore
import com.upgrid.browser.prefs.BrowserPreferences
import com.upgrid.browser.privacy.BrowsingDataCleaner
import com.upgrid.browser.privacy.ClearDataDialog
import com.upgrid.browser.privacy.SiteDataActivity
import com.upgrid.browser.search.SearchEngine
import com.upgrid.browser.search.SearchHistory
import com.upgrid.browser.sync.GoogleAccounts
import com.upgrid.browser.sync.SignInDiagnostics
import com.upgrid.browser.sync.SyncEngine
import com.upgrid.browser.sync.SyncOutcome
import com.upgrid.browser.ui.ExpandedBottomSheetFragment
import com.upgrid.browser.ui.ThemeMode
import com.upgrid.browser.ui.applyColorScheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything the user configures, on one scrolling sheet.
 *
 * This grew when the app menu was cut down: the AdBlock switch, bookmark and
 * history management and the Google account all moved here, leaving the menu
 * for things that act on the page in front of you.
 *
 * Still a bottom sheet rather than an Activity. It's long, but it's a flat list
 * of independent rows with no navigation between them — a screen would add a
 * back stack for nothing.
 */
class SettingsBottomSheet : ExpandedBottomSheetFragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as BrowserApplication
    private val components get() = app.components
    private val prefs by lazy { BrowserPreferences(app) }
    private val searchHistory by lazy { SearchHistory(app) }
    private val browsingHistory by lazy { HistoryStore(app) }
    private val bookmarks by lazy { BookmarkStore(app) }
    private val adblock by lazy { AdblockController(components) }
    private val cleaner by lazy { BrowsingDataCleaner(app, components) }

    /** Guards against a second tap while a sync round-trip is in flight. */
    private var syncing = false

    /**
     * Result of the Google sign-in activity.
     *
     * Registered as a property initializer, which runs during fragment
     * construction — [registerForActivityResult] refuses to run once the
     * fragment is STARTED, so this cannot move into onViewCreated.
     */
    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            GoogleAccounts.accountFromResult(result.data)
                .onSuccess {
                    renderAccount()
                    // Sync straight away: connecting an account and then having
                    // to find a second button to make anything happen is a
                    // strange half-finished state to leave the user in.
                    runSync()
                }
                .onFailure { showSignInFailure(it) }
        }

    /**
     * Result of the "grant Drive access" consent screen, which Google raises
     * separately from sign-in the first time a token is requested.
     */
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) runSync()
            else setSyncStatus(getString(R.string.settings_sync_consent_declined))
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wireAccount()
        wireAdblock()
        wirePrivacy()
        renderThemes()
        renderEngines()
        renderSeekSteps()
        wireBackgroundPlayback()
        wireDataButtons()
        renderAbout()

        renderAccount()
        renderCounts()
    }

    // --- Account & sync ----------------------------------------------------

    private fun wireAccount() {
        binding.btnAccountAction.setOnClickListener {
            if (GoogleAccounts.current(requireContext()) == null) {
                signInLauncher.launch(GoogleAccounts.signInIntent(requireContext()))
            } else {
                confirmDisconnect()
            }
        }
        binding.btnSyncNow.setOnClickListener { runSync() }
        // switchAutoSync's listener is attached by renderAccount, which has to
        // set the initial state and the listener together — see below.
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_account_disconnect_title)
            .setMessage(R.string.settings_account_disconnect_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_account_disconnect) { _, _ ->
                GoogleAccounts.signOut(requireContext()) {
                    // Callback lands whenever Play services is done; the sheet
                    // may be gone by then.
                    if (_binding != null) {
                        renderAccount()
                        setSyncStatus(getString(R.string.settings_account_disconnected))
                    }
                }
            }
            .show()
    }

    private fun renderAccount() {
        // Activity-result callbacks are delivered on the way back up the
        // lifecycle and can land before this fragment has a view again after
        // process death. Nothing to draw on in that case.
        _binding ?: return

        val account = GoogleAccounts.current(requireContext())
        val connected = account != null

        binding.accountName.text =
            account?.email ?: getString(R.string.settings_account_none)
        binding.accountStatus.setText(
            if (connected) R.string.settings_account_connected_hint
            else R.string.settings_account_hint
        )
        binding.btnAccountAction.setText(
            if (connected) R.string.settings_account_disconnect
            else R.string.settings_account_connect
        )
        binding.btnSyncNow.isEnabled = connected && !syncing

        // Set without the listener attached — assigning isChecked fires it, and
        // that would write the preference back on every render.
        binding.switchAutoSync.setCheckedSilently(prefs.autoSync) { _, checked ->
            prefs.autoSync = checked
        }
        binding.switchAutoSync.isEnabled = connected

        setSyncStatus(lastSyncLabel())
    }

    private fun lastSyncLabel(): String {
        val at = prefs.lastSyncAt
        if (at == 0L) return getString(R.string.settings_sync_never)
        val stamp = Instant.ofEpochMilli(at)
            .atZone(ZoneId.systemDefault())
            .format(SYNC_TIME_FORMAT)
        return getString(R.string.settings_sync_last, stamp)
    }

    private fun runSync() {
        if (syncing) return
        // Same reason as renderAccount: this is reachable from a launcher
        // callback, which doesn't guarantee a view.
        _binding ?: return
        if (GoogleAccounts.current(requireContext()) == null) {
            setSyncStatus(getString(R.string.settings_sync_needs_account))
            return
        }
        syncing = true
        binding.btnSyncNow.isEnabled = false
        setSyncStatus(getString(R.string.settings_sync_running))

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = SyncEngine(app).sync()
            syncing = false
            binding.btnSyncNow.isEnabled = true

            when (outcome) {
                is SyncOutcome.Success -> {
                    setSyncStatus(lastSyncLabel())
                    renderCounts()
                    // Merged-in bookmarks change what the speed dial shows.
                    (activity as? MainActivity)?.refreshStartPage()
                }
                is SyncOutcome.NeedsConsent -> consentLauncher.launch(outcome.intent)
                is SyncOutcome.NotSignedIn ->
                    setSyncStatus(getString(R.string.settings_sync_needs_account))
                is SyncOutcome.Failed ->
                    setSyncStatus(getString(R.string.settings_sync_failed, outcome.reason))
            }
        }
    }

    private fun setSyncStatus(text: String) {
        _binding?.syncStatus?.text = text
    }

    /**
     * A sign-in failure, in the status row.
     *
     * The wording lives in [SignInDiagnostics] because the app menu can start a
     * sign-in too, and two screens explaining the same Play-services status
     * code in two different ways is how one of them ends up wrong.
     * MainActivity goes further and offers the setup values for the
     * missing-OAuth-client case; here a line of text is enough, since Settings
     * is where you'd already be looking.
     */
    private fun showSignInFailure(error: Throwable) {
        setSyncStatus(SignInDiagnostics.describe(requireContext(), error))
    }

    // --- AdBlock -----------------------------------------------------------

    /**
     * The uBO switch. Reading its state is a suspending call into the engine
     * (see [AdblockController]) and there is no "set to X" — only a toggle — so
     * the switch drives it by comparing against the last rendered value.
     */
    private fun wireAdblock() {
        viewLifecycleOwner.lifecycleScope.launch {
            val on = adblock.isEnabled()
            _binding ?: return@launch
            binding.switchAdblock.setCheckedSilently(on) { _, checked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (adblock.isEnabled() != checked) runCatching { adblock.toggle() }
                }
            }
        }
    }

    // --- VPN, passwords, site data, downloads ------------------------------

    /**
     * The three sections that are really doors to other screens.
     *
     * Each keeps its summary line current — how much disk the browser is
     * sitting on, whether the tunnel is up, how many files are downloaded —
     * because a row that only says "Site data" makes you open it to find out
     * whether there's anything to do.
     */
    private fun wirePrivacy() {
        binding.btnOpenVpn.setOnClickListener {
            dismiss()
            (requireActivity() as MainActivity).showVpnSettings()
        }
        binding.btnOpenPasswords.setOnClickListener {
            dismiss()
            (requireActivity() as MainActivity).showLogins()
        }
        binding.switchSavePasswords.setCheckedSilently(prefs.savePasswords) { _, on ->
            prefs.savePasswords = on
        }

        binding.btnOpenSiteData.setOnClickListener {
            dismiss()
            startActivity(SiteDataActivity.intent(requireContext()))
        }
        binding.btnClearData.setOnClickListener {
            ClearDataDialog.show(requireActivity(), cleaner) {
                if (_binding != null) {
                    renderCounts()
                    (activity as? MainActivity)?.refreshStartPage()
                }
            }
        }

        binding.btnOpenDownloads.setOnClickListener {
            dismiss()
            (requireActivity() as MainActivity).showDownloads()
        }
    }

    // --- Pickers -----------------------------------------------------------

    /**
     * Light / dark / system.
     *
     * Picking one recreates every activity underneath this sheet, which takes
     * the sheet with it — that's AppCompat's doing and it's the right
     * behaviour, since a half-repainted browser would be worse. The engine gets
     * told separately so pages switch too; it survives the recreation because
     * it lives in the Application.
     */
    private fun renderThemes() {
        val current = prefs.themeMode
        binding.themeGroup.removeAllViews()
        ThemeMode.entries.forEach { mode ->
            binding.themeGroup.addView(
                radio(getString(mode.label), mode == current) {
                    prefs.themeMode = mode
                    components.engine.applyColorScheme(mode)
                    ThemeMode.apply(mode)
                }
            )
        }
    }

    /**
     * One radio per [SearchEngine], in declaration order. The list is short and
     * stable — a RecyclerView for four rows would be more moving parts, not
     * fewer.
     */
    private fun renderEngines() {
        val current = prefs.searchEngine
        binding.engineGroup.removeAllViews()
        SearchEngine.entries.forEach { engine ->
            binding.engineGroup.addView(
                radio(engine.displayName, engine == current) { prefs.searchEngine = engine }
            )
        }
    }

    /**
     * Keep-playing-in-the-background switch, next to the player's other
     * settings because that is where somebody looking for it would go.
     */
    private fun wireBackgroundPlayback() {
        binding.switchBackgroundPlayback.setCheckedSilently(prefs.backgroundPlayback) { _, on ->
            prefs.backgroundPlayback = on
        }
    }

    /** One radio per seek-step option for the built-in player. */
    private fun renderSeekSteps() {
        val current = prefs.playerSeekSeconds
        binding.seekStepGroup.removeAllViews()
        BrowserPreferences.PLAYER_SEEK_OPTIONS.forEach { seconds ->
            binding.seekStepGroup.addView(
                radio(
                    getString(R.string.settings_seek_option, seconds),
                    seconds == current,
                ) { prefs.playerSeekSeconds = seconds }
            )
        }
    }

    private fun radio(label: String, checked: Boolean, onPick: () -> Unit): RadioButton =
        RadioButton(requireContext()).apply {
            id = View.generateViewId()
            text = label
            textSize = 15f
            setPadding(paddingLeft + dp(12), dp(9), paddingRight, dp(9))
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> if (isChecked) onPick() }
            layoutParams = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    // --- Data management ---------------------------------------------------

    private fun wireDataButtons() {
        binding.btnOpenBookmarks.setOnClickListener {
            dismiss()
            (requireActivity() as MainActivity).showBookmarks()
        }
        binding.btnClearBookmarks.setOnClickListener {
            confirm(R.string.settings_clear_bookmarks, R.string.settings_clear_bookmarks_message) {
                viewLifecycleOwner.lifecycleScope.launch {
                    bookmarks.clearAll()
                    renderCounts()
                    (activity as? MainActivity)?.refreshStartPage()
                }
            }
        }

        binding.btnOpenHistory.setOnClickListener {
            dismiss()
            (requireActivity() as MainActivity).showHistory()
        }
        binding.btnClearBrowsing.setOnClickListener {
            confirm(R.string.settings_clear_browsing, R.string.history_clear_all_message) {
                viewLifecycleOwner.lifecycleScope.launch {
                    browsingHistory.clearAll()
                    renderCounts()
                }
            }
        }

        binding.btnClearHistory.setOnClickListener {
            searchHistory.clear()
            renderCounts()
        }
    }

    private fun confirm(titleRes: Int, messageRes: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.history_clear_all_confirm) { _, _ -> onConfirm() }
            .show()
    }

    /** One pass over every count shown on this sheet. */
    private fun renderCounts() {
        val searches = searchHistory.recent().size
        binding.historyCount.text = resources.getQuantityString(
            R.plurals.settings_history_count, searches, searches
        )
        binding.btnClearHistory.isEnabled = searches > 0

        val files = components.downloadRecords.records.value.size
        binding.downloadsCount.text =
            resources.getQuantityString(R.plurals.settings_downloads_count, files, files)

        binding.vpnSummary.text = getString(
            if (components.vpn.isUp) R.string.settings_vpn_on else R.string.settings_vpn_off,
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val pages = browsingHistory.count()
            val saved = bookmarks.count()
            _binding ?: return@launch
            binding.browsingCount.text = resources.getQuantityString(
                R.plurals.settings_pages_count, pages, pages
            )
            binding.btnClearBrowsing.isEnabled = pages > 0
            binding.bookmarksCount.text = resources.getQuantityString(
                R.plurals.settings_bookmarks_count, saved, saved
            )
            binding.btnClearBookmarks.isEnabled = saved > 0

            // Walking the profile directory takes long enough to be worth not
            // blocking the rest of the sheet on it.
            val bytes = cleaner.approximateCacheBytes()
            _binding ?: return@launch
            binding.dataSummary.text = getString(
                R.string.settings_data_size,
                android.text.format.Formatter.formatShortFileSize(requireContext(), bytes),
            )
        }
    }

    /** Which build this is. Pairs with the version stamped on the APK CI posts. */
    private fun renderAbout() {
        binding.versionLabel.text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "settings"

        private val SYNC_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
    }
}

/**
 * Assign a checked state without the change listener firing.
 *
 * `isChecked = x` notifies the listener, so a render pass that reflects stored
 * state would immediately write that state back — harmless for a preference,
 * but for the AdBlock switch it means every render toggles uBO.
 */
private fun CompoundButton.setCheckedSilently(
    value: Boolean,
    listener: CompoundButton.OnCheckedChangeListener,
) {
    setOnCheckedChangeListener(null)
    isChecked = value
    setOnCheckedChangeListener(listener)
}
