package com.upgrid.browser.tabs

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemTabStripBinding
import com.upgrid.browser.ui.HostTile
import mozilla.components.browser.state.state.TabSessionState

/**
 * The open tabs, across the top, the way a desktop browser has them.
 *
 * Tablets only. The point of it is that switching costs one tap: on a phone the
 * tab counter opens a whole screen, which is the right trade when there is no
 * room for anything else, and the wrong one when there are four inches of
 * empty bar sitting above the page.
 *
 * Deliberately simple next to the tabs screen: no previews, no filter, no
 * list/grid choice. This is the thing you glance at, and everything it does is
 * one of two taps — switch, or close. The counter button is still there for
 * everything else.
 *
 * `notifyDataSetChanged` rather than DiffUtil, as everywhere else in this app:
 * the list is the number of tabs a person keeps open, the rebind is a title and
 * a favicon, and a diff would be more code than the thing it optimises.
 */
class TabStripAdapter(
    private val onClick: (TabSessionState) -> Unit,
    private val onClose: (TabSessionState) -> Unit,
) : RecyclerView.Adapter<TabStripAdapter.Holder>() {

    private var tabs: List<TabSessionState> = emptyList()
    private var selectedId: String? = null

    /** Index of the selected tab, or -1. The strip scrolls to keep it in view. */
    val selectedPosition: Int get() = tabs.indexOfFirst { it.id == selectedId }

    @SuppressWarnings("NotifyDataSetChanged")
    fun submit(tabs: List<TabSessionState>, selectedId: String?) {
        this.tabs = tabs
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tabs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemTabStripBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tab = tabs[position]
        holder.bind(tab, tab.id == selectedId, onClick, onClose)
    }

    class Holder(private val binding: ItemTabStripBinding) :
        RecyclerView.ViewHolder(binding.root) {

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

            binding.stripTitle.text = when {
                isBlank -> context.getString(R.string.menu_new_tab)
                else -> tab.content.title.ifBlank { host.ifBlank { url } }
            }

            // The tint has to be put back rather than merely cleared: a
            // recycled row that showed a favicon left it null, and ic_globe is
            // a white vector — on a light theme that is an invisible icon.
            val favicon = tab.content.icon
            if (favicon != null) {
                binding.stripFavicon.setImageBitmap(favicon)
                binding.stripFavicon.imageTintList = null
            } else {
                binding.stripFavicon.setImageResource(
                    if (isBlank) R.drawable.ic_add else R.drawable.ic_globe,
                )
                binding.stripFavicon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        binding.stripFavicon,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
            }

            binding.root.isSelected = isSelected
            binding.root.setOnClickListener { onClick(tab) }
            binding.btnStripClose.setOnClickListener { onClose(tab) }
        }
    }
}
