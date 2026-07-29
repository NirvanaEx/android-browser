package com.upgrid.browser.tabs

import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemTabStripBinding
import com.upgrid.browser.databinding.ItemTabStripNewBinding
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
 * **Tabs share the strip's width; they do not have one.** That is the single
 * thing that makes a row of these read as tabs rather than as a list of chips,
 * and it is what the first version got wrong: fixed-width tabs left half the
 * strip empty with three open and started scrolling at six, while every desktop
 * browser instead squeezes them until they hit a floor. So the width is
 * `strip / count`, clamped to [R.dimen.tab_strip_tab_min] and
 * [R.dimen.tab_strip_tab_max], and only once every tab is at the floor does the
 * strip begin to scroll.
 *
 * Deliberately simple next to the tabs screen: no previews, no filter, no
 * list/grid choice. This is the thing you glance at, and everything it does is
 * one of two taps — switch, or close. The counter button is still there for
 * everything else.
 *
 * `notifyDataSetChanged` rather than DiffUtil, as everywhere else in this app:
 * the list is the number of tabs a person keeps open, the rebind is a title and
 * a favicon, and a diff would be more code than the thing it optimises. It is
 * also the honest call here — one tab opening changes the width of every other
 * one.
 */
class TabStripAdapter(
    private val onClick: (TabSessionState) -> Unit,
    private val onClose: (TabSessionState) -> Unit,
    /** The trailing "+", which is a row of this list rather than a button beside it. */
    private val onNewTab: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var tabs: List<TabSessionState> = emptyList()
    private var selectedId: String? = null

    /** How much room the strip has for tabs. 0 until it has been laid out. */
    private var stripWidth = 0

    /** Resolved once, from the first view to be bound. */
    private var minWidth = 0
    private var maxWidth = 0
    private var closeMinWidth = 0
    private var newTabWidth = 0

    /** Index of the selected tab, or -1. The strip scrolls to keep it in view. */
    val selectedPosition: Int get() = tabs.indexOfFirst { it.id == selectedId }

    @SuppressWarnings("NotifyDataSetChanged")
    fun submit(tabs: List<TabSessionState>, selectedId: String?, stripWidth: Int) {
        this.tabs = tabs
        this.selectedId = selectedId
        this.stripWidth = stripWidth
        notifyDataSetChanged()
    }

    /** The tabs, plus one row for the "+" that follows them. */
    override fun getItemCount(): Int = tabs.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == tabs.size) TYPE_NEW_TAB else TYPE_TAB

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_NEW_TAB) {
            NewTabHolder(ItemTabStripNewBinding.inflate(inflater, parent, false))
        } else {
            Holder(ItemTabStripBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        readDimensions(holder.itemView.resources)

        if (holder is NewTabHolder) {
            holder.bind(onNewTab)
            return
        }
        if (holder !is Holder) return

        val tab = tabs[position]
        val selected = tab.id == selectedId
        val width = widthForOneTab()

        holder.bind(
            tab = tab,
            isSelected = selected,
            tabWidth = width,
            // The ✕ costs a third of a narrow tab. Dropping it from the
            // background ones keeps their titles readable, and the tab you are
            // actually looking at is the one you are most likely to close.
            showClose = selected || width >= closeMinWidth,
            // A hairline divides two neighbours. Next to the selected tab there
            // is already an edge, and after the last one there is nothing to
            // divide from.
            showDivider = !selected &&
                position != selectedPosition - 1 &&
                position != tabs.lastIndex,
            onClick = onClick,
            onClose = onClose,
        )
    }

    /**
     * Chrome's rule: divide what there is, then refuse to go below the floor
     * and start scrolling instead.
     */
    private fun widthForOneTab(): Int {
        if (stripWidth <= 0 || tabs.isEmpty()) return maxWidth
        // The "+" is reserved out of the width first, or the last tab would sit
        // underneath it at exactly the moment the strip fills up.
        val forTabs = (stripWidth - newTabWidth).coerceAtLeast(minWidth)
        return (forTabs / tabs.size).coerceIn(minWidth, maxWidth)
    }

    private fun readDimensions(res: Resources) {
        if (minWidth != 0) return
        minWidth = res.getDimensionPixelSize(R.dimen.tab_strip_tab_min)
        maxWidth = res.getDimensionPixelSize(R.dimen.tab_strip_tab_max)
        closeMinWidth = res.getDimensionPixelSize(R.dimen.tab_strip_close_min)
        newTabWidth = res.getDimensionPixelSize(R.dimen.tab_strip_new_width)
    }

    /** The trailing "+". Nothing to render — it only needs its listener. */
    class NewTabHolder(private val binding: ItemTabStripNewBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(onNewTab: () -> Unit) {
            binding.btnStripNewTab.setOnClickListener { onNewTab() }
        }
    }

    class Holder(private val binding: ItemTabStripBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            tab: TabSessionState,
            isSelected: Boolean,
            // Not `width`: inside updateLayoutParams the receiver is the
            // LayoutParams, whose own `width` would shadow it — and
            // `this.width = width` would then be a silent self-assignment.
            tabWidth: Int,
            showClose: Boolean,
            showDivider: Boolean,
            onClick: (TabSessionState) -> Unit,
            onClose: (TabSessionState) -> Unit,
        ) {
            val url = tab.content.url
            val isBlank = url.isBlank() || url == MainActivity.HOME_URL
            val host = HostTile.hostOf(url)
            val context = binding.root.context

            binding.root.updateLayoutParams { width = tabWidth }

            binding.stripTitle.text = when {
                isBlank -> context.getString(R.string.menu_new_tab)
                else -> tab.content.title.ifBlank { host.ifBlank { url } }
            }
            // The tab in front carries the page's own text colour; the ones
            // behind it step back to the muted one. Same signal the shape
            // gives, said a second way, because on a light theme the shape
            // alone is a very quiet difference.
            binding.stripTitle.setTextColor(
                MaterialColors.getColor(
                    binding.stripTitle,
                    if (isSelected) {
                        com.google.android.material.R.attr.colorOnSurface
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    },
                ),
            )

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

            binding.btnStripClose.isVisible = showClose
            binding.stripDivider.isVisible = showDivider

            binding.stripChip.isSelected = isSelected
            binding.stripChip.setOnClickListener { onClick(tab) }
            binding.btnStripClose.setOnClickListener { onClose(tab) }
        }
    }

    private companion object {
        const val TYPE_TAB = 0
        const val TYPE_NEW_TAB = 1
    }
}
