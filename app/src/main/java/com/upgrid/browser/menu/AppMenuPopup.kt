package com.upgrid.browser.menu

import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.upgrid.browser.AdblockController
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.AppMenuPopupBinding
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab

/**
 * Banana-style app menu drop-down. Replaces the old MenuBottomSheet — same
 * actions, but rendered as a 300dp-wide PopupWindow anchored to the menu
 * button so it reads as a proper "branded" menu rather than a system-style
 * bottom sheet.
 *
 * Lifecycle: instantiate, call [showFrom] with the anchor view, that's it.
 * The popup is focusable so the system back button and outside-tap dismiss it
 * for free. Toggle rows (AdBlock, Desktop site) intentionally don't dismiss
 * the popup so the user can see the pill flip in place; everything else
 * dismisses on tap to keep the UX snappy.
 */
class AppMenuPopup(private val activity: MainActivity) {

    private val components get() = (activity.application as BrowserApplication).components
    private val adblock by lazy { AdblockController(components) }
    private val binding =
        AppMenuPopupBinding.inflate(LayoutInflater.from(activity))

    private val popup: PopupWindow

    init {
        val density = activity.resources.displayMetrics.density
        popup = PopupWindow(
            binding.root,
            (300 * density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true, // focusable: back / outside tap → dismiss
        ).apply {
            setBackgroundDrawable(
                ContextCompat.getDrawable(activity, R.drawable.bg_popup_menu)
            )
            elevation = 12f * density
            // Tighter dismiss: tapping outside the popup closes it without
            // delivering the touch to the underlying view (avoids accidentally
            // re-opening the menu via the menu button).
            isOutsideTouchable = true
        }

        wireRows()
        renderToggleStates()
    }

    /**
     * Show the menu anchored to [anchor] (typically `btnMenu` in the bottom
     * bar). showAsDropDown auto-flips above the anchor when there's no room
     * below, which is exactly what we want for a bottom-bar trigger.
     *
     * Gravity.END right-aligns the popup with the anchor's right edge so the
     * 300dp menu floats at the right side of the screen, matching Banana.
     */
    fun showFrom(anchor: View) {
        // PopupWindow doesn't auto-update its content the next time it's
        // shown if state changed in the meantime — refresh toggles each show.
        renderToggleStates()
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    // --- Wiring ------------------------------------------------------------

    private fun wireRows() = with(binding) {
        rowNewTab.setOnClickListener {
            components.tabsUseCases.addTab(url = MainActivity.HOME_URL, selectTab = true)
            popup.dismiss()
        }
        rowShare.setOnClickListener {
            shareCurrentUrl()
            popup.dismiss()
        }
        rowFindInPage.setOnClickListener {
            activity.showFindInPage()
            popup.dismiss()
        }
        rowRefresh.setOnClickListener {
            components.sessionUseCases.reload()
            popup.dismiss()
        }
        rowDesktopSite.setOnClickListener {
            // Toggle in place; don't dismiss so the user sees the pill flip.
            val tab = components.store.state.selectedTab ?: return@setOnClickListener
            val newValue = !tab.content.desktopMode
            components.sessionUseCases.requestDesktopSite(newValue, tab.id)
            renderDesktopSiteState(newValue)
        }
        rowAdblock.setOnClickListener {
            // Same: toggle in place. Also propagates state back to the
            // bottom-bar shield since the activity no longer polls per tick.
            activity.lifecycleScope.launch {
                runCatching { adblock.toggle() }
                renderAdblockState()
                activity.renderAdblockShield()
            }
        }
        rowCloseTab.setOnClickListener {
            components.store.state.selectedTabId?.let {
                components.tabsUseCases.removeTab(it)
            }
            popup.dismiss()
        }

        // Phase-3 stubs — toast for now, dismissed so the toast isn't trapped
        // behind the popup.
        rowBookmarks.setOnClickListener { stubToast(); popup.dismiss() }
        rowHistory.setOnClickListener { stubToast(); popup.dismiss() }
        rowDownloads.setOnClickListener { stubToast(); popup.dismiss() }
        rowSettings.setOnClickListener { stubToast(); popup.dismiss() }
    }

    private fun stubToast() {
        Toast.makeText(activity, R.string.coming_soon, Toast.LENGTH_SHORT).show()
    }

    // --- Toggle render -----------------------------------------------------

    private fun renderToggleStates() {
        renderAdblockState()
        renderDesktopSiteState()
    }

    /**
     * uBO state lookup is suspending (see AdblockController) — fire it on the
     * activity's lifecycle scope so we don't race with engine callbacks.
     */
    private fun renderAdblockState() {
        activity.lifecycleScope.launch {
            val on = adblock.isEnabled()
            binding.adblockIcon.setImageResource(
                if (on) R.drawable.ic_shield else R.drawable.ic_shield_off
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
}
