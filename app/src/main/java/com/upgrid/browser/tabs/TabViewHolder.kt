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
 * One tab card in the grid.
 *
 * Three layers of identity, best first: the page preview if we have one, the
 * favicon in the footer, and the coloured letter tile behind both. Favicons are
 * read straight off [TabSessionState.content]'s icon — GeckoView populates it on
 * `<link rel=icon>` parse and it lives in the BrowserStore, so it costs nothing
 * here. We deliberately skip
 * [mozilla.components.browser.icons.BrowserIcons.loadIntoView]: in a-c 150 it
 * nulls the ImageView before its async fetch starts, which flashed an empty
 * circle for every tab whose favicon hadn't loaded yet.
 */
class TabViewHolder private constructor(
    private val binding: ItemTabCardBinding,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(
        tab: TabSessionState,
        isSelected: Boolean,
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

        // A brand-new tab has no host to take a letter or a colour from, so it
        // gets the app's own accent and a "+" rather than the "?" an unknown
        // host would produce — it isn't unknown, it's empty.
        binding.tabInitial.text = if (isBlank) "+" else HostTile.letterFor(host)
        binding.tabInitial.backgroundTintList = ColorStateList.valueOf(
            if (isBlank) NEW_TAB_TILE else HostTile.colorFor(host)
        )

        val preview = thumbnails[tab.id]
        binding.tabThumb.isVisible = preview != null
        if (preview != null) binding.tabThumb.setImageBitmap(preview)

        val icon = tab.content.icon
        if (icon != null) {
            binding.tabFavicon.setImageBitmap(icon)
            // Real favicons are full-colour — drop the placeholder tint, which
            // would otherwise repaint them a flat grey.
            binding.tabFavicon.imageTintList = null
        } else {
            binding.tabFavicon.setImageResource(R.drawable.ic_globe)
            binding.tabFavicon.imageTintList = ColorStateList.valueOf(
                if (isBlank) NEW_TAB_TILE else HostTile.colorFor(host)
            )
        }

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
