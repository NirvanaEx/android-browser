package com.upgrid.browser.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemQuickLinkBinding
import com.upgrid.browser.databinding.ViewStartPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.icons.IconRequest

/**
 * Owns the start page view tree.
 *
 * The grid is the user's bookmarks, topped up from [QuickLink.SEED] so a fresh
 * install isn't empty and saving one page doesn't blank the other seven tiles.
 * A trailing "+" tile adds a shortcut; long-press removes one.
 *
 * Favicons are loaded through [BrowserIcons], which caches in memory and on
 * disk — so the network is touched once per site, not once per visit to the
 * start page. Each tile keeps its coloured letter underneath the icon, which is
 * what makes the grid readable in the moment before the icons resolve.
 */
class StartPagePresenter(
    private val binding: ViewStartPageBinding,
    private val icons: BrowserIcons,
    /** Host's lifecycle scope — icon fetches must die with the screen. */
    private val scope: CoroutineScope,
    private val onLinkClick: (QuickLink) -> Unit,
    private val onLinkLongClick: (QuickLink) -> Unit,
    private val onAddClick: () -> Unit,
) {

    /**
     * What the grid currently shows. Guards against the redundant rebuild that
     * `onResume` would otherwise cause on every return to the app: re-inflating
     * eight tiles is cheap, but re-requesting eight icons and re-running their
     * fade-in is visible.
     */
    private var rendered: List<QuickLink>? = null

    init { setLinks(QuickLink.SEED) }

    /** Replace the grid. A no-op when the links haven't actually changed. */
    fun setLinks(links: List<QuickLink>) {
        if (links == rendered) return
        rendered = links

        val grid = binding.quickLinksGrid
        grid.removeAllViews()
        val inflater = LayoutInflater.from(grid.context)

        // Chunk into rows of 4 to keep the layout dependency-free — an 8-cell
        // static grid isn't worth pulling in androidx.gridlayout for. The "+"
        // rides along as a final entry so it wraps with everything else.
        val cells: List<QuickLink?> = links + listOf(null)
        cells.chunked(COLUMNS).forEach { row ->
            val rowView = LinearLayout(grid.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
            }
            row.forEach { link ->
                val tile = ItemQuickLinkBinding.inflate(inflater, rowView, false)
                if (link == null) bindAddTile(tile) else bindLink(tile, link)
                rowView.addView(tile.root)
            }
            grid.addView(rowView)
        }
    }

    private fun bindLink(tile: ItemQuickLinkBinding, link: QuickLink) {
        tile.tileLetter.text = link.letter
        tile.tileLetter.isVisible = true
        tile.tileBg.backgroundTintList = ColorStateList.valueOf(link.color)
        tile.tileLabel.text = link.label
        tile.tileIcon.isVisible = false
        tile.root.setOnClickListener { onLinkClick(link) }
        tile.root.setOnLongClickListener { onLinkLongClick(link); true }

        // loadIntoView is avoided here for the same reason as in the tabs grid:
        // in a-c 150 it nulls the ImageView before its fetch starts, which
        // would blink the letter away and back. Awaiting the Deferred and
        // setting the bitmap ourselves keeps the tile stable.
        scope.launch {
            val icon = runCatching {
                icons.loadIcon(IconRequest(url = link.url)).await()
            }.getOrNull() ?: return@launch
            // The grid may have been rebuilt under a slow fetch.
            if (rendered?.contains(link) != true) return@launch
            tile.tileIcon.setImageBitmap(icon.bitmap)
            tile.tileIcon.isVisible = true
            tile.tileLetter.isVisible = false
        }
    }

    /** The trailing "+" cell. Not a link, so it has no icon and no long-press. */
    private fun bindAddTile(tile: ItemQuickLinkBinding) {
        tile.tileLetter.text = "+"
        tile.tileLetter.isVisible = true
        tile.tileIcon.isVisible = false
        tile.tileBg.backgroundTintList = ColorStateList.valueOf(ADD_TILE_COLOR)
        tile.tileLabel.setText(R.string.start_page_add)
        tile.root.setOnClickListener { onAddClick() }
        tile.root.setOnLongClickListener(null)
        tile.root.isLongClickable = false
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

        /** Muted grey so "+" reads as an affordance, not as another site. */
        val ADD_TILE_COLOR = 0xFF8A8F98.toInt()
    }
}
