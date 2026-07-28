package com.upgrid.browser.tabs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityTabsBinding
import com.upgrid.browser.prefs.BrowserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.flow

/**
 * The tab switcher, in either of two views.
 *
 * Cards with page previews and a plain list are both right answers and which
 * one is right depends on how many tabs you keep: previews are how you find one
 * of six, a list is how you get through thirty. The switch in the header moves
 * between them and the choice is remembered, so this is a preference the user
 * sets by using it rather than one buried in Settings.
 *
 * The header is laid out like Chrome's, on request — ⊞ left, the switch in the
 * middle carrying the count, ⋮ right, filter underneath. See the note at the
 * top of activity_tabs.xml for what each position is for.
 *
 * Selecting or closing tabs only dispatches to the shared
 * [mozilla.components.browser.state.store.BrowserStore]; MainActivity renders
 * whatever comes out, so there is no result to hand back and nothing to keep in
 * sync — finishing is enough.
 */
class TabsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTabsBinding
    private val components get() = (application as BrowserApplication).components
    private val preferences by lazy { BrowserPreferences(this) }
    private lateinit var adapter: TabsAdapter

    /** Everything open, unfiltered — the count on the switch is drawn from it. */
    private var allTabs: List<TabSessionState> = emptyList()
    private var selectedTabId: String? = null

    /** What is in the filter field. Empty means "show everything". */
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTabsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The one additive action of this screen, and the only reason to come
        // here that isn't about a tab already in the list.
        binding.btnTabsNew.setOnClickListener {
            components.tabsUseCases.addTab(url = MainActivity.HOME_URL, selectTab = true)
            finish()
        }

        // A switch with a position, not two buttons: tapping the half that is
        // already on does nothing rather than toggling to the other one.
        binding.btnViewGrid.setOnClickListener { setGrid(true) }
        binding.btnViewList.setOnClickListener { setGrid(false) }

        // Closing every tab at once is the one irreversible thing here, so it
        // is behind the ⋮ rather than sitting under a resting thumb.
        binding.btnTabsMenu.setOnClickListener { showOverflow(it) }

        binding.search.searchInput.setHint(R.string.tabs_tray_search_hint)
        binding.search.searchInput.doAfterTextChanged {
            query = it?.toString().orEmpty()
            renderTabs()
        }

        adapter = TabsAdapter(
            icons = components.icons,
            scope = lifecycleScope,
            thumbnails = components.tabThumbnails,
            onClick = { tab ->
                components.tabsUseCases.selectTab(tab.id)
                finish()
            },
            onClose = { tab ->
                components.tabThumbnails.remove(tab.id)
                components.tabsUseCases.removeTab(tab.id)
            },
        )
        binding.tabsList.adapter = adapter
        // Rows and cards are each a fixed size regardless of position, so
        // RecyclerView can skip re-measuring the whole list on every insert and
        // remove — closing several tabs in a row is visibly janky otherwise.
        binding.tabsList.setHasFixedSize(true)
        applyLayout()
        attachSwipeToClose()

        observeTabs()
        wireBackPress()
    }

    private fun setGrid(grid: Boolean) {
        if (preferences.tabsGrid == grid) return
        preferences.tabsGrid = grid
        applyLayout()
    }

    /**
     * Put the chosen view on screen.
     *
     * The recycled view pool is cleared because it holds inflated cards when
     * the list is about to want rows: the pool is keyed by view type, so stale
     * entries would simply sit there holding bitmaps until the screen closes.
     */
    private fun applyLayout() {
        val grid = preferences.tabsGrid
        adapter.grid = grid
        binding.tabsList.layoutManager = if (grid) {
            GridLayoutManager(this, COLUMNS)
        } else {
            LinearLayoutManager(this)
        }
        binding.tabsList.recycledViewPool.clear()
        adapter.notifyDataSetChanged()
        // The switch shows where it is, not where it could go.
        binding.btnViewGrid.isSelected = grid
        binding.btnViewList.isSelected = !grid
    }

    /** Close-everything, one step away from the thumb. */
    private fun showOverflow(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, R.string.tabs_tray_close_all)
            setOnMenuItemClickListener {
                components.tabsUseCases.removeAllTabs()
                true
            }
            show()
        }
    }

    /**
     * Swipe a row aside to close that tab.
     *
     * List only. The ✕ is still there and is still the discoverable way; this
     * is the one every other tab list has taught, and closing five tabs with
     * five swipes beats hitting a 40dp target five times. In the grid a
     * sideways swipe is the gesture for scrolling between columns as far as the
     * hand is concerned, so it stays off.
     */
    private fun attachSwipeToClose() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.START or ItemTouchHelper.END,
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (preferences.tabsGrid) 0 else super.getSwipeDirs(recyclerView, viewHolder)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter.tabAt(viewHolder.bindingAdapterPosition)?.let { tab ->
                    components.tabThumbnails.remove(tab.id)
                    components.tabsUseCases.removeTab(tab.id)
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.tabsList)
    }

    private fun observeTabs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                components.store.flow()
                    .map { it.tabs to it.selectedTabId }
                    .distinctUntilChanged()
                    .collect { (tabs, selectedId) ->
                        allTabs = tabs
                        selectedTabId = selectedId
                        renderTabs()
                        // Previews for tabs that no longer exist are just memory.
                        components.tabThumbnails.retainOnly(tabs.map { it.id }.toSet())
                    }
            }
        }
    }

    /**
     * Draw whatever the filter left, and say the right thing when that is
     * nothing.
     *
     * "You have no tabs" and "no tab matches what you typed" are different
     * situations with different answers, and one empty state that says the
     * first while the second is true reads as the browser having lost them.
     *
     * The count on the switch stays the real one. It is the number of tabs
     * open, not the number currently shown; a filter is a way of looking, not a
     * change to what is there.
     */
    private fun renderTabs() {
        val shown = filtered()
        adapter.submit(shown, selectedTabId)
        binding.tabsCount.text = allTabs.size.toString()
        binding.tabsList.isVisible = shown.isNotEmpty()
        binding.emptyState.isVisible = shown.isEmpty()
        binding.search.root.isVisible = allTabs.isNotEmpty()

        val searching = query.isNotBlank() && allTabs.isNotEmpty()
        binding.emptyTitle.setText(
            if (searching) R.string.tabs_tray_no_matches_title else R.string.tabs_tray_empty_title,
        )
        binding.emptySubtitle.setText(
            if (searching) {
                R.string.tabs_tray_no_matches_subtitle
            } else {
                R.string.tabs_tray_empty_subtitle
            },
        )
    }

    /**
     * Title or address, case-insensitively. Both, because half the time you
     * remember the site and half the time you remember the headline — and a
     * tab that hasn't loaded yet has an address and nothing else.
     */
    private fun filtered(): List<TabSessionState> {
        val needle = query.trim()
        if (needle.isBlank()) return allTabs
        return allTabs.filter { tab ->
            tab.content.title.contains(needle, ignoreCase = true) ||
                tab.content.url.contains(needle, ignoreCase = true)
        }
    }

    /**
     * Leaving with zero tabs would drop MainActivity onto an unrendered engine
     * view, so it gets a fresh start page instead. Same on the back gesture and
     * on the back arrow, which is why it lives here rather than in a click
     * handler — [finish] is reached from three places.
     */
    private fun wireBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    override fun finish() {
        if (components.store.state.tabs.isEmpty()) {
            components.tabsUseCases.addTab(url = MainActivity.HOME_URL, selectTab = true)
        }
        super.finish()
    }

    companion object {
        /** Two columns on a phone: three makes the title one word wide. */
        private const val COLUMNS = 2

        fun intent(context: Context): Intent = Intent(context, TabsActivity::class.java)
    }
}

private class TabsAdapter(
    private val icons: BrowserIcons,
    private val scope: CoroutineScope,
    private val thumbnails: TabThumbnails,
    private val onClick: (TabSessionState) -> Unit,
    private val onClose: (TabSessionState) -> Unit,
) : RecyclerView.Adapter<TabViewHolder>() {

    private var tabs: List<TabSessionState> = emptyList()
    private var selectedId: String? = null

    /** Which of the two views to inflate. Set by the screen, never guessed. */
    var grid: Boolean = true

    fun submit(tabs: List<TabSessionState>, selectedId: String?) {
        this.tabs = tabs
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    /** The tab at a list position, or null if the list moved under a gesture. */
    fun tabAt(position: Int): TabSessionState? = tabs.getOrNull(position)

    override fun getItemViewType(position: Int): Int = if (grid) TYPE_CARD else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder =
        if (viewType == TYPE_CARD) TabViewHolder.card(parent) else TabViewHolder.row(parent)

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        holder.bind(
            tab = tab,
            isSelected = tab.id == selectedId,
            icons = icons,
            scope = scope,
            thumbnails = thumbnails,
            onClick = onClick,
            onClose = onClose,
        )
    }

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_CARD = 1
    }
}
