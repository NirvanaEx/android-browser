package com.upgrid.browser.menu

import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.upgrid.browser.AdblockController
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.bookmarks.BookmarkStore
import com.upgrid.browser.databinding.AppMenuPopupBinding
import com.upgrid.browser.settings.SettingsBottomSheet
import com.upgrid.browser.sync.GoogleAccounts
import com.upgrid.browser.vpn.VpnFormat
import com.upgrid.browser.vpn.VpnStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab

/**
 * The app menu: a 264dp drop-down anchored to the ⋮ button.
 *
 * Lifecycle: instantiate, call [showFrom] with the anchor, done. The popup is
 * focusable so system back and outside-tap dismiss it for free. Toggle rows
 * (AdBlock, Desktop site, the bookmark star) deliberately do NOT dismiss — the
 * point of a toggle is watching it flip.
 *
 * A fresh instance per tap. Cheap, and it means no stale toggle state to
 * invalidate: everything is read at construction and again in [showFrom].
 *
 * One thing here is not read once but *collected*: the VPN speed. Everything
 * else in this menu is a fact that can't change while you're looking at it; the
 * tunnel's throughput is the exception, and a frozen number there reads as a
 * dead connection. The job is cancelled on dismiss.
 */
class AppMenuPopup(private val activity: MainActivity) {

    private val components get() = (activity.application as BrowserApplication).components
    private val adblock by lazy { AdblockController(components) }

    // Through components, not a fresh BookmarkStore: constructing one opens a
    // second SQLiteOpenHelper against a file the activity already has open, and
    // this class is instantiated on every tap of the ⋮ button.
    private val bookmarks get() = components.bookmarks

    private val binding =
        AppMenuPopupBinding.inflate(LayoutInflater.from(activity))

    private val popup: PopupWindow

    /** Live VPN readout, collected only while the menu is on screen. */
    private var vpnJob: Job? = null

    init {
        val density = activity.resources.displayMetrics.density
        popup = PopupWindow(
            binding.root,
            (MENU_WIDTH_DP * density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true, // focusable: back / outside tap → dismiss
        ).apply {
            setBackgroundDrawable(
                ContextCompat.getDrawable(activity, R.drawable.bg_popup_menu)
            )
            elevation = 12f * density
            isOutsideTouchable = true
            // Grows out of the ⋮ it was opened from instead of appearing whole.
            // PopupWindow's default is no animation at all, which on a panel
            // this size reads as the screen flickering.
            animationStyle = R.style.Animation_UpgridBrowser_Menu
        }

        wireQuickActions()
        wireRows()
    }

    /**
     * Show the menu anchored to [anchor]. Gravity.END right-aligns it with the
     * anchor's right edge; showAsDropDown flips it above the anchor when
     * there's no room below.
     */
    fun showFrom(anchor: View) {
        // PopupWindow keeps its content view between shows, so anything derived
        // from browser state has to be re-read here rather than at construction.
        renderState()
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)

        // The one row that changes while you look at it. Collected here rather
        // than read once because the speed is the point — a frozen number is
        // indistinguishable from a stalled tunnel.
        vpnJob = activity.lifecycleScope.launch {
            components.vpnStatus.state.collect(::renderVpnState)
        }
        popup.setOnDismissListener { vpnJob?.cancel() }
    }

    // --- Wiring ------------------------------------------------------------

    private fun wireQuickActions() = with(binding) {
        quickBack.setOnClickListener {
            components.sessionUseCases.goBack()
            popup.dismiss()
        }
        quickForward.setOnClickListener {
            components.sessionUseCases.goForward()
            popup.dismiss()
        }
        quickReload.setOnClickListener {
            components.sessionUseCases.reload()
            popup.dismiss()
        }
        quickBookmark.setOnClickListener {
            val tab = components.store.state.selectedTab ?: return@setOnClickListener
            val url = tab.content.url
            if (!BookmarkStore.isBookmarkable(url)) {
                Toast.makeText(activity, R.string.bookmark_not_saveable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activity.lifecycleScope.launch {
                val saved = bookmarks.toggle(url, tab.content.title)
                renderBookmarkState(saved)
                Toast.makeText(
                    activity,
                    if (saved) R.string.bookmark_added else R.string.bookmark_removed,
                    Toast.LENGTH_SHORT,
                ).show()
                // The speed dial is backed by bookmarks, so it has to be told.
                activity.refreshStartPage()
            }
        }
    }

    private fun wireRows() = with(binding) {
        rowNewTab.setOnClickListener {
            components.tabsUseCases.addTab(url = MainActivity.HOME_URL, selectTab = true)
            popup.dismiss()
        }
        rowPrivateTab.setOnClickListener {
            popup.dismiss()
            activity.openPrivateTab()
        }
        rowBookmarks.setOnClickListener {
            popup.dismiss()
            activity.showBookmarks()
        }
        rowHistory.setOnClickListener {
            popup.dismiss()
            activity.showHistory()
        }
        rowDownloads.setOnClickListener {
            popup.dismiss()
            activity.showDownloads()
        }
        rowProfile.setOnClickListener {
            popup.dismiss()
            activity.showAccount()
        }
        rowVpn.setOnClickListener {
            // Connecting needs the consent dialog, which needs an Activity —
            // so the whole decision lives there. Unconfigured goes to setup.
            popup.dismiss()
            activity.toggleVpn()
        }
        rowShare.setOnClickListener {
            shareCurrentUrl()
            popup.dismiss()
        }
        rowFindInPage.setOnClickListener {
            activity.showFindInPage()
            popup.dismiss()
        }
        rowTranslate.setOnClickListener {
            popup.dismiss()
            activity.toggleTranslation()
        }
        rowDesktopSite.setOnClickListener {
            // Toggle in place; don't dismiss so the user sees the pill flip.
            val tab = components.store.state.selectedTab ?: return@setOnClickListener
            val newValue = !tab.content.desktopMode
            components.sessionUseCases.requestDesktopSite(newValue, tab.id)
            renderDesktopSiteState(newValue)
        }
        rowAdblock.setOnClickListener {
            activity.lifecycleScope.launch {
                runCatching { adblock.toggle() }
                renderAdblockState()
            }
        }
        rowAccount.setOnClickListener {
            popup.dismiss()
            if (GoogleAccounts.current(activity) == null) {
                // The launcher has to live on the activity — a PopupWindow has
                // no lifecycle to register an activity-result contract against.
                activity.connectGoogleAccount()
            } else {
                // Already connected: the only thing left to do here is manage
                // it, which lives in Settings next to sync-now and auto-sync.
                SettingsBottomSheet().show(activity.supportFragmentManager, SettingsBottomSheet.TAG)
            }
        }
        rowSettings.setOnClickListener {
            popup.dismiss()
            SettingsBottomSheet().show(activity.supportFragmentManager, SettingsBottomSheet.TAG)
        }
    }

    // --- State render ------------------------------------------------------

    private fun renderState() {
        val tab = components.store.state.selectedTab
        binding.quickBack.setEnabledLook(tab?.content?.canGoBack == true)
        binding.quickForward.setEnabledLook(tab?.content?.canGoForward == true)

        // Signed-in state is a cheap local lookup (Play services caches it), so
        // it's safe on the main thread here. The row shows the account's own
        // address when connected — that IS the status indicator.
        val account = GoogleAccounts.current(activity)
        binding.accountState.isVisible = account != null
        if (account != null) {
            binding.accountLabel.text =
                account.email ?: activity.getString(R.string.menu_account_connected)
        } else {
            binding.accountLabel.setText(R.string.menu_account_connect)
        }

        renderAdblockState()
        renderDesktopSiteState()
        renderProfileState()
        renderVpnState(components.vpnStatus.state.value)
        renderDownloadsState()
        renderBookmarkStateFromDb(tab?.content?.url)

        // One row, two meanings: on a translated page it offers the original
        // back. On anything that isn't a web page — the start page, about: —
        // there's no content script to talk to, so the row is hidden rather
        // than shown doing nothing.
        val url = tab?.content?.url.orEmpty()
        val translatable = url.startsWith("http://") || url.startsWith("https://")
        val translated = activity.isPageTranslated()
        binding.rowTranslate.isVisible = translatable
        binding.rowFindInPage.isVisible = translatable
        binding.translateLabel.setText(
            if (translated) R.string.menu_translate_original else R.string.menu_translate
        )
    }

    private fun View.setEnabledLook(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
    }

    /**
     * uBO state is a suspending lookup (see [AdblockController]) — run it on the
     * activity's scope so we don't race the engine's extension callbacks.
     */
    private fun renderAdblockState() {
        activity.lifecycleScope.launch {
            val on = adblock.isEnabled()
            binding.adblockIcon.setImageResource(
                if (on) R.drawable.ic_shield else R.drawable.ic_shield_off
            )
            binding.adblockIcon.setColorFilter(
                MaterialColors.getColor(
                    binding.adblockIcon,
                    if (on) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            )
            binding.adblockState.setText(
                if (on) R.string.menu_adblock_state_on else R.string.menu_adblock_state_off
            )
        }
    }

    /**
     * The menu's header: monogram, name, and what the account has done for you.
     *
     * It doubles as the readout — "am I signed in?" is answered by opening the
     * menu rather than by navigating somewhere to find out — and the second
     * line says whether the sign-in actually brought a VPN profile with it,
     * which is the only reason the account exists.
     */
    private fun renderProfileState() {
        val account = components.accounts.current
        binding.profileAvatar.text = monogram(account?.name)
        if (account == null) {
            binding.profileLabel.setText(R.string.menu_account_sign_in)
            binding.profileState.setText(R.string.settings_profile_hint)
        } else {
            binding.profileLabel.text = account.name
            binding.profileState.setText(
                if (components.accounts.vpnConfig != null) {
                    R.string.settings_profile_provisioned
                } else {
                    R.string.settings_profile_bare
                },
            )
        }
    }

    /**
     * The VPN row: shield on and "ON" when the tunnel is up, a live speed under
     * the label while it is, and a third state for "nothing set up yet" —
     * offering to connect a profile that doesn't exist would be a switch that
     * can only fail.
     */
    private fun renderVpnState(status: VpnStatus.Snapshot) {
        val up = status.up
        val configured = components.vpnSettings.isConfigured
        binding.vpnIcon.setImageResource(
            if (up) R.drawable.ic_shield else R.drawable.ic_shield_off
        )
        binding.vpnIcon.setColorFilter(
            MaterialColors.getColor(
                binding.vpnIcon,
                if (up) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        )
        binding.vpnState.setText(
            when {
                up -> R.string.menu_adblock_state_on
                configured -> R.string.menu_adblock_state_off
                else -> R.string.menu_vpn_state_unset
            }
        )
        binding.vpnSpeed.isVisible = up && status.sampled
        if (up && status.sampled) {
            binding.vpnSpeed.text = VpnFormat.rate(activity, status)
        }
    }

    /** First letter of the account's name, or a placeholder glyph. */
    private fun monogram(name: String?): String {
        val letter = name?.trim()?.firstOrNull() ?: return "?"
        return letter.uppercase()
    }

    /** How many files are on the downloads list, or nothing when it's empty. */
    private fun renderDownloadsState() {
        val count = components.downloadRecords.records.value.size
        binding.downloadsState.isVisible = count > 0
        binding.downloadsState.text = count.toString()
    }

    private fun renderDesktopSiteState(value: Boolean? = null) {
        val on = value ?: (components.store.state.selectedTab?.content?.desktopMode == true)
        binding.desktopSiteState.setText(
            if (on) R.string.menu_adblock_state_on else R.string.menu_adblock_state_off
        )
    }

    private fun renderBookmarkStateFromDb(url: String?) {
        if (url == null || !BookmarkStore.isBookmarkable(url)) {
            binding.quickBookmark.setEnabledLook(false)
            renderBookmarkState(false)
            return
        }
        binding.quickBookmark.setEnabledLook(true)
        activity.lifecycleScope.launch { renderBookmarkState(bookmarks.isBookmarked(url)) }
    }

    private fun renderBookmarkState(saved: Boolean) {
        binding.quickBookmark.setImageResource(
            if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
        )
        binding.quickBookmark.setColorFilter(
            MaterialColors.getColor(
                binding.quickBookmark,
                if (saved) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface,
            )
        )
        binding.quickBookmark.contentDescription = activity.getString(
            if (saved) R.string.menu_bookmark_remove else R.string.menu_bookmark_add
        )
    }

    // --- Helpers -----------------------------------------------------------

    private fun shareCurrentUrl() {
        val tab = components.store.state.selectedTab ?: return
        val url = tab.content.url
        val title = tab.content.title.ifBlank { url }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        activity.startActivity(
            Intent.createChooser(intent, activity.getString(R.string.menu_share))
        )
    }

    private companion object {
        /**
         * Down from the original 300dp, then back up 28 when the profile
         * header arrived: an account name and a live VPN speed both want more
         * than a one-word label, and at 236 either would have been an ellipsis.
         * Still well short of covering the page.
         */
        const val MENU_WIDTH_DP = 264
    }
}
