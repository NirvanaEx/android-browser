package com.upgrid.browser.tabs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
 * of six, a list is how you get through thirty. The icon in the header switches
 * them and the choice is remembered, so this is a preference the user sets by
 * using it rather than one buried in Settings.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTabsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.tabs_tray_title)
        binding.header.btnBack.setOnClickListener { finish() }

        // The one additive action of this screen, and the only reason to come
        // here that isn't about a tab already in the list.
        binding.header.btnHeaderIcon.isVisible = true
        binding.header.btnHeaderIcon.contentDescription = getString(R.string.tabs_tray_new)
        binding.header.btnHeaderIcon.setOnClickListener {
            components.tabsUseCases.addTab(url = MainActivity.HOME_URL, selectTab = true)
            finish()
        }

        binding.header.btnHeaderToggle.isVisible = true
        binding.header.btnHeaderToggle.setOnClickListener {
            preferences.tabsGrid = !preferences.tabsGrid
            applyLayout()
        }

        binding.header.btnHeaderAction.setText(R.string.tabs_tray_close_all)
        binding.header.btnHeaderAction.setOnClickListener {
            components.tabsUseCases.removeAllTabs()
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
        // The icon shows what the other view is — a switch, not a status.
        binding.header.btnHeaderToggle.setImageResource(
            if (grid) R.drawable.ic_list else R.drawable.ic_grid,
        )
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
                        adapter.submit(tabs, selectedId)
                        binding.header.pageTitle.text = if (tabs.isEmpty()) {
                            getString(R.string.tabs_tray_title)
                        } else {
                            getString(R.string.tabs_tray_title_count, tabs.size)
                        }
                        binding.header.btnHeaderAction.isVisible = tabs.isNotEmpty()
                        binding.header.btnHeaderToggle.isVisible = tabs.isNotEmpty()
                        binding.tabsList.isVisible = tabs.isNotEmpty()
                        binding.emptyState.isVisible = tabs.isEmpty()
                        // Previews for tabs that no longer exist are just memory.
                        components.tabThumbnails.retainOnly(tabs.map { it.id }.toSet())
                    }
            }
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
