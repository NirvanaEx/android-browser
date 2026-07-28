package com.upgrid.browser.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.upgrid.browser.databinding.ItemQuickLinkBinding
import com.upgrid.browser.databinding.ViewStartPageBinding

/**
 * Owns the start page view tree.
 *
 * The speed dial is backed by the user's bookmarks, topped up from
 * [QuickLink.SEED] when they have fewer than a full grid. A first-run user gets
 * the same eight defaults as before; someone with three bookmarks gets those
 * three plus five defaults, rather than a page that empties out the moment they
 * save something. [setLinks] is called by MainActivity whenever bookmarks
 * change.
 */
class StartPagePresenter(
    private val binding: ViewStartPageBinding,
    private val onLinkClick: (QuickLink) -> Unit,
) {

    init { setLinks(QuickLink.SEED) }

    /** Replace the grid. Cheap enough to call on every bookmark edit. */
    fun setLinks(links: List<QuickLink>) {
        val grid = binding.quickLinksGrid
        grid.removeAllViews()
        val inflater = LayoutInflater.from(grid.context)
        // Chunk into rows of 4 to keep the layout dependency-free — an 8-cell
        // static grid isn't worth pulling in androidx.gridlayout for.
        links.chunked(COLUMNS).forEach { row ->
            val rowView = LinearLayout(grid.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
            }
            row.forEach { link ->
                val tile = ItemQuickLinkBinding.inflate(inflater, rowView, false)
                tile.tileLetter.text = link.letter
                tile.tileBg.backgroundTintList = ColorStateList.valueOf(link.color)
                tile.tileLabel.text = link.label
                tile.root.setOnClickListener { onLinkClick(link) }
                rowView.addView(tile.root)
            }
            grid.addView(rowView)
        }
    }

    val root: View get() = binding.root

    /** Show / hide the whole start page; cheap enough to call on every state tick. */
    fun setVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (root.visibility != target) root.visibility = target
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    private companion object {
        const val COLUMNS = 4
    }
}
