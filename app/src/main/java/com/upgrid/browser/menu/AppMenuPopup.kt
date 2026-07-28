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
import com.upgrid.browser.translate.PageTranslator
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab

/**
 * The app menu: a 236dp drop-down anchored to the ⋮ button.
 *
 * Lifecycle: instantiate, call [showFrom] with the anchor, done. The popup is
 * focusable so system back and outside-tap dismiss it for free. Toggle rows
 * (AdBlock, Desktop site, the bookmark star) deliberately do NOT dismiss — the
 * point of a toggle is watching it flip.
 *
 * A fresh instance per tap. Cheap, and it means no stale toggle state to
 * invalidate: everything is read at construction and again in [showFrom].
 */
class AppMenuPopup(private val activity: MainActivity) {

    private val components get() = (activity.application as BrowserApplication).components
    private val adblock by lazy { AdblockController(components) }
    private val bookmarks by lazy { BookmarkStore(activity) }
    private val binding =
        AppMenuPopupBinding.inflate(LayoutInflater.from(activity))

    private val popup: PopupWindow

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
        rowBookmarks.setOnClickListener {
            popup.dismiss()
            activity.showBookmarks()
        }
        rowHistory.setOnClickListener {
            popup.dismiss()
            activity.showHistory()
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
        renderBookmarkStateFromDb(tab?.content?.url)

        // One row, two meanings: on a translated page it offers the original
        // back. Anywhere the proxy can't go — the start page, a file:// URL —
        // the row is hidden rather than shown doing nothing.
        val url = tab?.content?.url.orEmpty()
        val translated = PageTranslator.isTranslated(url)
        binding.rowTranslate.isVisible =
            translated || PageTranslator.toTranslated(url) != null
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
         * Down from 300dp. The menu is a list of short labels; the extra width
         * bought nothing but a wall of dropdown covering most of the page.
         */
        const val MENU_WIDTH_DP = 236
    }
}
