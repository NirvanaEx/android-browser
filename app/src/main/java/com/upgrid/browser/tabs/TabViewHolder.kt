package com.upgrid.browser.tabs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemTabRowBinding
import com.upgrid.browser.ui.HostTile
import kotlinx.coroutines.CoroutineScope
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.state.state.TabSessionState

/**
 * One tab row.
 *
 * The favicon comes from [TabSessionState.content] when the tab has one —
 * GeckoView fills it in on `<link rel=icon>` parse and it lives in the
 * BrowserStore, so it costs nothing here and it is the exact icon that tab is
 * showing. Tabs restored from a previous session have none yet, and those fall
 * through to the shared favicon loader like every other list in the app.
 */
class TabViewHolder private constructor(
    private val binding: ItemTabRowBinding,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(
        tab: TabSessionState,
        isSelected: Boolean,
        icons: BrowserIcons,
        scope: CoroutineScope,
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

    companion object {
        fun inflate(parent: ViewGroup): TabViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return TabViewHolder(ItemTabRowBinding.inflate(inflater, parent, false))
        }
    }
}
