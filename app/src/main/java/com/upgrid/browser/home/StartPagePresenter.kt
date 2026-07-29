package com.upgrid.browser.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemQuickLinkBinding
import com.upgrid.browser.databinding.ItemStartRecentBinding
import com.upgrid.browser.databinding.ViewStartPageBinding
import com.upgrid.browser.ui.Favicons
import com.upgrid.browser.ui.HostTile
import com.upgrid.browser.ui.Motion
import com.upgrid.browser.ui.setVisibleAnimated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.BrowserIcons

/**
 * Owns the start page view tree.
 *
 * Two lists, both of them the user's own: the speed dial, and the last few
 * pages they were on. The grid is their bookmarks, topped up from
 * [QuickLink.SEED] so a fresh install isn't empty and saving one page doesn't
 * blank the other seven tiles; a trailing "+" adds a shortcut and a long press
 * removes one.
 *
 * Favicons are loaded through [BrowserIcons] with [Favicons.request], which is
 * what makes them real logos rather than coloured letters — see that file.
 * Caching is in memory and on disk, so the network is touched once per site,
 * not once per visit to this screen. The letter stays underneath either way,
 * which is what makes the grid readable in the moment before the icons resolve.
 *
 * The number of columns is a resource ([R.integer.start_page_columns]), not a
 * constant: four on a phone, six on a tablet. Cells divide their row rather
 * than being a fixed width, and a short last row is padded out with spacers, so
 * the grid lines up in both cases and at every screen width in between.
 */
class StartPagePresenter(
    private val binding: ViewStartPageBinding,
    private val icons: BrowserIcons,
    /** Host's lifecycle scope — icon fetches must die with the screen. */
    private val scope: CoroutineScope,
    private val onLinkClick: (QuickLink) -> Unit,
    private val onLinkLongClick: (QuickLink) -> Unit,
    private val onAddClick: () -> Unit,
    /** A row in "recent" — load it, same as a tile. */
    private val onRecentClick: (RecentPage) -> Unit,
    /** The row under them: the whole history screen. */
    private val onAllHistory: () -> Unit,
) {

    /** One recently visited page. Title and address; the icon is fetched. */
    data class RecentPage(val title: String, val url: String)

    /**
     * What the grid currently shows. Guards against the redundant rebuild that
     * `onResume` would otherwise cause on every return to the app: re-inflating
     * eight tiles is cheap, but re-requesting eight icons and re-running their
     * fade-in is visible.
     */
    private var rendered: List<QuickLink>? = null

    /** Same guard for the recent list, which is re-read on every resume. */
    private var renderedRecent: List<RecentPage>? = null

    /**
     * What [setVisible] was last asked for. Not derived from the view's own
     * visibility, which lags behind by the length of the fade — asking twice
     * for the same thing has to be free, and this is called from a store
     * observer.
     */
    private var shown = false

    private val columns = binding.root.resources.getInteger(R.integer.start_page_columns)

    init {
        binding.btnAllHistory.setOnClickListener { onAllHistory() }
        setLinks(QuickLink.SEED)
    }

    /** Replace the grid. A no-op when the links haven't actually changed. */
    fun setLinks(links: List<QuickLink>) {
        if (links == rendered) return
        rendered = links

        val grid = binding.quickLinksGrid
        grid.removeAllViews()
        val inflater = LayoutInflater.from(grid.context)

        // Chunked into rows to keep the layout dependency-free — a static grid
        // this small isn't worth pulling in androidx.gridlayout for. The "+"
        // rides along as a final entry so it wraps with everything else.
        val cells: List<QuickLink?> = links + listOf(null)
        cells.chunked(columns).forEach { row ->
            val rowView = LinearLayout(grid.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
            }
            row.forEach { link ->
                val tile = ItemQuickLinkBinding.inflate(inflater, rowView, false)
                if (link == null) bindAddTile(tile) else bindLink(tile, link)
                rowView.addView(tile.root, cellParams())
            }
            // A last row of two tiles must sit under the first two of the row
            // above, not spread itself across the card.
            repeat(columns - row.size) { rowView.addView(View(grid.context), cellParams()) }
            grid.addView(rowView)
        }
    }

    /**
     * The last few pages, under the shortcuts.
     *
     * The whole section goes away when there are none: a captioned empty box
     * on a fresh install says the browser is missing something rather than
     * that you haven't been anywhere yet.
     */
    fun setRecent(pages: List<RecentPage>) {
        if (pages == renderedRecent) return
        renderedRecent = pages

        binding.recentSection.isVisible = pages.isNotEmpty()
        val list = binding.recentList
        list.removeAllViews()
        if (pages.isEmpty()) return

        val inflater = LayoutInflater.from(list.context)
        pages.forEach { page ->
            val row = ItemStartRecentBinding.inflate(inflater, list, false)
            val host = HostTile.hostOf(page.url)
            row.recentTitle.text = page.title.ifBlank { host.ifBlank { page.url } }
            row.recentHost.text = host
            row.recentHost.isVisible = host.isNotBlank()
            row.recentIcon.bindSite(page.url, icons, scope)
            row.root.setOnClickListener { onRecentClick(page) }
            list.addView(row.root)
        }
    }

    private fun cellParams() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

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
                icons.loadIcon(Favicons.request(link.url)).await()
            }.getOrNull() ?: return@launch
            // The grid may have been rebuilt under a slow fetch.
            if (rendered?.contains(link) != true) return@launch
            tile.tileIcon.setImageBitmap(icon.bitmap)
            // Faded in over the letter rather than swapped: the icons arrive
            // one at a time, over about a second, and eight tiles changing
            // face in a stutter is the thing that made this grid look cheap.
            tile.tileIcon.setVisibleAnimated(true, Motion.QUICK)
            tile.tileLetter.setVisibleAnimated(false, Motion.QUICK)
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
        if (visible == shown) return
        shown = visible
        root.setVisibleAnimated(visible, Motion.STANDARD)
        if (visible) playEntrance()
    }

    /**
     * The page arrives a section at a time.
     *
     * Only on the way in, and only 45 ms apart: this is the screen you see
     * every time you open a tab, and an entrance you have to wait through
     * becomes an entrance you resent by the fourth tab. Short enough that it
     * reads as the page settling rather than as a sequence.
     */
    private fun playEntrance() {
        val grid = binding.quickLinksGrid
        val rise = ENTRANCE_RISE_DP * grid.resources.displayMetrics.density
        val rows = buildList {
            for (index in 0 until grid.childCount) add(grid.getChildAt(index))
            if (binding.recentSection.isVisible) add(binding.recentSection)
        }
        rows.forEachIndexed { index, row ->
            row.animate().cancel()
            row.alpha = 0f
            row.translationY = rise
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * ENTRANCE_STAGGER_MS)
                .setDuration(Motion.STANDARD)
                .setInterpolator(Motion.EASE_OUT)
                .start()
        }
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    private companion object {
        /** Muted grey so "+" reads as an affordance, not as another site. */
        val ADD_TILE_COLOR = 0xFF8A8F98.toInt()

        const val ENTRANCE_RISE_DP = 10f
        const val ENTRANCE_STAGGER_MS = 45L
    }
}
