package com.upgrid.browser.tabs

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemTabCardBinding
import com.upgrid.browser.ui.HostTile
import mozilla.components.browser.state.state.TabSessionState

/**
 * One tab card in the tray grid.
 *
 * Favicons are read straight off [TabSessionState.content]'s icon — GeckoView
 * populates it on `<link rel=icon>` parse and it lives in the BrowserStore, so
 * it's free here. We deliberately skip
 * [mozilla.components.browser.icons.BrowserIcons.loadIntoView]: in a-c 150 it
 * nulls the ImageView before its async fetch starts, which flashed an empty
 * circle for every tab whose favicon hadn't loaded yet.
 *
 * The letter tile underneath is not a fallback that gets replaced — it stays
 * painted behind the favicon, so a site with a transparent or tiny icon still
 * reads as a solid, per-host colored block from across the grid.
 */
class TabViewHolder private constructor(
    private val binding: ItemTabCardBinding,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(
        tab: TabSessionState,
        isSelected: Boolean,
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
        binding.tabHost.text = if (isBlank) "" else host.ifBlank { url }

        // A brand-new tab has no host to take a letter or a color from, so it
        // gets the app's own accent and a "+" rather than the "?" an unknown
        // host would produce — it isn't unknown, it's empty.
        binding.tabInitial.text = if (isBlank) "+" else HostTile.letterFor(host)
        binding.tabInitial.backgroundTintList = ColorStateList.valueOf(
            if (isBlank) NEW_TAB_TILE else HostTile.colorFor(host)
        )

        val icon = tab.content.icon
        binding.tabFavicon.isVisible = icon != null
        if (icon != null) binding.tabFavicon.setImageBitmap(icon)

        binding.root.isSelected = isSelected
        binding.root.setOnClickListener { onClick(tab) }
        binding.btnClose.setOnClickListener { onClose(tab) }
    }

    companion object {
        /** Brand blue; matches colors.xml's brand_blue without a resource lookup.
         *  Not `const` — `.toInt()` on an out-of-Int-range literal isn't a
         *  compile-time constant. */
        private val NEW_TAB_TILE = 0xFF1F6FEB.toInt()

        fun inflate(parent: ViewGroup): TabViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return TabViewHolder(ItemTabCardBinding.inflate(inflater, parent, false))
        }
    }
}
