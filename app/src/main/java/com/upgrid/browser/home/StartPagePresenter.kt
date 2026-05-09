package com.upgrid.browser.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.children
import com.upgrid.browser.databinding.ItemQuickLinkBinding
import com.upgrid.browser.databinding.ViewStartPageBinding

/**
 * Owns the start page view tree.
 *
 * Inflates speed-dial tiles into the existing [ViewStartPageBinding.quickLinksGrid]
 * container in 4-per-row chunks. Each tile's letter background is tinted from
 * [QuickLink.color]; tap calls back through [onLinkClick] which the host wires
 * up to [SessionUseCases.loadUrl].
 */
class StartPagePresenter(
    private val binding: ViewStartPageBinding,
    private val onLinkClick: (QuickLink) -> Unit,
) {

    init { populateGrid(QuickLink.SEED) }

    private fun populateGrid(links: List<QuickLink>) {
        val grid = binding.quickLinksGrid
        grid.removeAllViews()
        val inflater = LayoutInflater.from(grid.context)
        // Chunk into rows of 4 to keep the layout dependency-free.
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
        if (root.visibility != if (visible) View.VISIBLE else View.GONE) {
            root.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    /** Walk inflated tiles to update labels/colors when SEED changes (future-proof). */
    @Suppress("unused")
    fun refresh() {
        binding.quickLinksGrid.children.flatMap { (it as LinearLayout).children }.forEach {
            // No-op for now; left as the obvious extension point when bookmarks back the seed.
        }
    }

    private companion object {
        const val COLUMNS = 4
    }
}
