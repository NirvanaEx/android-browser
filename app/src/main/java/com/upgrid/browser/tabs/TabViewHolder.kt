package com.upgrid.browser.tabs

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemTabCardBinding
import com.upgrid.browser.databinding.ItemTabRowBinding
import com.upgrid.browser.ui.HostTile
import kotlinx.coroutines.CoroutineScope
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.state.state.TabSessionState

/**
 * One tab, in whichever of the two views the screen is showing.
 *
 * The favicon comes from [TabSessionState.content] when the tab has one —
 * GeckoView fills it in on `<link rel=icon>` parse and it lives in the
 * BrowserStore, so it costs nothing here and it is the exact icon that tab is
 * showing. Tabs restored from a previous session have none yet, and those fall
 * through to the shared favicon loader like every other list in the app.
 */
sealed class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {

    abstract fun bind(
        tab: TabSessionState,
        isSelected: Boolean,
        icons: BrowserIcons,
        scope: CoroutineScope,
        thumbnails: TabThumbnails,
        onClick: (TabSessionState) -> Unit,
        onClose: (TabSessionState) -> Unit,
    )

    /** The list view: leading square, title, host, ✕. Reads like history. */
    class Row(private val binding: ItemTabRowBinding) : TabViewHolder(binding.root) {

        override fun bind(
            tab: TabSessionState,
            isSelected: Boolean,
            icons: BrowserIcons,
            scope: CoroutineScope,
            thumbnails: TabThumbnails,
            onClick: (TabSessionState) -> Unit,
            onClose: (TabSessionState) -> Unit,
        ) {
            val url = tab.content.url
            val isBlank = url.isBlank() || url == MainActivity.HOME_URL
            val host = HostTile.hostOf(url)
            val context = binding.root.context

            binding.tabTitle.text = when {
                isBlank -> context.getString(R.string.menu_new_tab)
                else -> tab.content.title.ifBlank { host.ifBlank { url } }
            }
            binding.tabHost.text = when {
                isBlank -> context.getString(R.string.tabs_tray_blank_subtitle)
                else -> host.ifBlank { url }
            }

            if (isBlank) {
                binding.tabIcon.bindGlyph(R.drawable.ic_add)
            } else {
                binding.tabIcon.bindSite(url, tab.content.icon, icons, scope)
            }

            binding.tabSelectedBar.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            binding.root.setOnClickListener { onClick(tab) }
            binding.btnClose.setOnClickListener { onClose(tab) }
        }
    }

    /**
     * The grid view: caption on top, page preview underneath.
     *
     * The preview is whatever was captured the last time that tab was on
     * screen. Everything else — a restored session, a tab opened in the
     * background — has none, and shows the same site square the list does.
     */
    class Card(private val binding: ItemTabCardBinding) : TabViewHolder(binding.root) {

        override fun bind(
            tab: TabSessionState,
            isSelected: Boolean,
            icons: BrowserIcons,
            scope: CoroutineScope,
            thumbnails: TabThumbnails,
            onClick: (TabSessionState) -> Unit,
            onClose: (TabSessionState) -> Unit,
        ) {
            val url = tab.content.url
            val isBlank = url.isBlank() || url == MainActivity.HOME_URL
            val host = HostTile.hostOf(url)
            val context = binding.root.context

            binding.tabTitle.text = when {
                isBlank -> context.getString(R.string.menu_new_tab)
                else -> tab.content.title.ifBlank { host.ifBlank { url } }
            }

            // The tint has to be put back, not just cleared: a recycled card
            // that showed a favicon left it null, and ic_globe is a white
            // vector — on a light theme it would be an invisible icon.
            val favicon = tab.content.icon
            if (favicon != null) {
                binding.tabFavicon.setImageBitmap(favicon)
                binding.tabFavicon.imageTintList = null
            } else {
                binding.tabFavicon.setImageResource(R.drawable.ic_globe)
                binding.tabFavicon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        binding.tabFavicon,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
            }

            if (isBlank) {
                binding.tabPlaceholder.bindGlyph(R.drawable.ic_add)
            } else {
                binding.tabPlaceholder.bindSite(url, favicon, icons, scope)
            }

            val thumbnail = thumbnails[tab.id]
            binding.tabThumb.isVisible = thumbnail != null
            binding.tabThumb.setImageBitmap(thumbnail)

            binding.root.isSelected = isSelected
            binding.root.setOnClickListener { onClick(tab) }
            binding.btnClose.setOnClickListener { onClose(tab) }
        }
    }

    companion object {
        fun row(parent: ViewGroup): TabViewHolder =
            Row(ItemTabRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        fun card(parent: ViewGroup): TabViewHolder =
            Card(ItemTabCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }
}
