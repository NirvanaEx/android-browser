package com.upgrid.browser.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import com.upgrid.browser.account.AccountController
import com.upgrid.browser.BuildConfig
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.FragmentSettingsBinding
import com.upgrid.browser.prefs.BrowserPreferences
import com.upgrid.browser.privacy.BrowsingDataCleaner
import com.upgrid.browser.privacy.ClearDataDialog
import com.upgrid.browser.privacy.SiteDataActivity
import com.upgrid.browser.search.SearchEngine
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

    // Through components, never constructed here. Each of these owns a
    // SQLiteOpenHelper, and a fresh one per sheet is a second connection and a
    // second page cache against a file MainActivity already has open.
    private val searchHistory get() = components.searchHistory
    private val browsingHistory get() = components.browsingHistory
    private val bookmarks get() = components.bookmarks

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

        wireProfile()
        wireAccount()
        wireAdblock()
        wirePrivacy()
        renderThemes()
        renderEngines()
        wireLinkHandling()
        wireKeepAlive()
        renderSeekSteps()
        wireBackgroundPlayback()
        wireDataButtons()
        renderAbout()

        renderAccount()
        renderCounts()
    }

    override fun onResume() {
        super.onResume()
        // The battery exemption is granted on a system screen, so the answer
        // can change while this sheet is open and behind it.
        if (_binding != null) renderKeepAlive()
    }

    // --- The browser's own account -----------------------------------------

    /**
     * Sign in / sign out of the browser account, and say what it did.
     *
     * Above the Google section on purpose: this is the one that decides what
     * the browser is set up with, Google only syncs data into it.
     */
    private fun wireProfile() {
        renderProfile()
        binding.btnProfileAction.setOnClickListener {
            val account = components.accounts.current
            if (account == null) {
                dismiss()
                (requireActivity() as MainActivity).showAccount()
            } else {
                confirmSignOut(account.name)
            }
        }
    }

    private fun renderProfile() {
        val account = components.accounts.current
        binding.profileAvatar.text = account?.name?.trim()?.firstOrNull()?.uppercase() ?: "?"
        binding.profileName.text =
            account?.name ?: getString(R.string.settings_profile_none)
        binding.profileStatus.setText(
            when {
                account == null -> R.string.settings_profile_hint
                components.accounts.vpnConfig != null -> R.string.settings_profile_provisioned
                else -> R.string.settings_profile_bare
            },
        )
        binding.btnProfileAction.setText(
            if (account == null) R.string.account_sign_in else R.string.account_sign_out,
        )
    }

    private fun confirmSignOut(name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_sign_out_title)
            .setMessage(getString(R.string.account_sign_out_message, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.account_sign_out) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AccountController(components).signOut()
                    if (_binding != null) {
                        renderProfile()
                        renderCounts()
                    }
                }
            }
            .show()
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
     * What happens when a page asks for a window of its own, and whether a
     * followed link is felt.
     *
     * Both live in the search card rather than one of their own: this section
     * is about what happens when you go somewhere, and that is what both of
     * them decide.
     *
     * Two radio rows rather than a switch, because "off" here does not mean
     * "nothing happens" — the link still opens, just in the tab you're already
     * in — and a switch labelled "open links in a new tab" reads as though
     * turning it off breaks the link. Naming both positions says what each one
     * does.
     */
    private fun wireLinkHandling() {
        val auto = prefs.openLinksInNewTab
        binding.linkModeGroup.removeAllViews()
        binding.linkModeGroup.addView(
            radio(getString(R.string.settings_links_auto), auto) {
                prefs.openLinksInNewTab = true
            },
        )
        binding.linkModeGroup.addView(
            radio(getString(R.string.settings_links_same_tab), !auto) {
                prefs.openLinksInNewTab = false
            },
        )

        binding.switchHaptics.setCheckedSilently(prefs.hapticFeedback) { _, on ->
            prefs.hapticFeedback = on
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

    // --- Staying open ------------------------------------------------------

    /**
     * The one row in Settings that changes an Android setting rather than one
     * of ours.
     *
     * "I left the browser for two minutes and my page had to load again" is not
     * a browser bug and cannot be fixed inside the browser. Android decides
     * which background processes to kill, some phones far more eagerly than
     * others, and once ours is gone the most a browser can do is come back to
     * the same address — which is what the session snapshot does. The only
     * thing that stops the kill is the battery exemption, and it belongs to the
     * user to give.
     *
     * So the row states what the system is currently doing, and opens the
     * system's own dialog to change it. It does not nag: no prompt on launch,
     * no banner. It is here for the moment somebody goes looking for why.
     */
    private fun wireKeepAlive() {
        renderKeepAlive()
        binding.btnKeepAlive.setOnClickListener {
            if (keptAlive()) openBatterySettings() else requestKeepAlive()
        }
    }

    private fun renderKeepAlive() {
        binding.keepAliveStatus.setText(
            if (keptAlive()) R.string.settings_keep_alive_on else R.string.settings_keep_alive_off,
        )
    }

    private fun keptAlive(): Boolean {
        val power = requireContext().getSystemService(Context.POWER_SERVICE) as? PowerManager
        return power?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
    }

    /**
     * The system's one-tap "allow?" dialog. Some manufacturers ship without the
     * activity that answers this intent, so a refusal to start it falls through
     * to the full list, where the app can still be found by hand.
     */
    private fun requestKeepAlive() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${requireContext().packageName}"))
        runCatching { startActivity(direct) }.onFailure { openBatterySettings() }
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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

        // The endpoint matters as much as the state: "connected" without
        // saying where is half an answer, and the row has space for both.
        val endpoint = components.vpnSettings.endpoint
        val state = getString(
            when {
                components.vpn.isUp -> R.string.settings_vpn_on
                components.vpnSettings.isConfigured -> R.string.settings_vpn_off
                else -> R.string.vpn_not_configured
            },
        )
        binding.vpnSummary.text =
            if (endpoint.isBlank()) state else getString(R.string.settings_vpn_via, state, endpoint)

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
