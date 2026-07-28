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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityTabsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.flow

/**
 * The tab switcher: a list of open tabs.
 *
 * Selecting or closing tabs only dispatches to the shared
 * [mozilla.components.browser.state.store.BrowserStore]; MainActivity renders
 * whatever comes out, so there is no result to hand back and nothing to keep in
 * sync — finishing is enough.
 */
class TabsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTabsBinding
    private val components get() = (application as BrowserApplication).components
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

        binding.header.btnHeaderAction.setText(R.string.tabs_tray_close_all)
        binding.header.btnHeaderAction.setOnClickListener {
            components.tabsUseCases.removeAllTabs()
        }

        adapter = TabsAdapter(
            icons = components.icons,
            scope = lifecycleScope,
            onClick = { tab ->
                components.tabsUseCases.selectTab(tab.id)
                finish()
            },
            onClose = { tab -> components.tabsUseCases.removeTab(tab.id) },
        )
        binding.tabsList.layoutManager = LinearLayoutManager(this)
        binding.tabsList.adapter = adapter
        // Rows are a fixed height regardless of position, so RecyclerView can
        // skip re-measuring the whole list on every insert and remove — closing
        // several tabs in a row is visibly janky otherwise.
        binding.tabsList.setHasFixedSize(true)
        attachSwipeToClose()

        observeTabs()
        wireBackPress()
    }

    /**
     * Swipe a row aside to close that tab.
     *
     * The ✕ is still there and is still the discoverable way; this is the one
     * every other tab list has taught, and closing five tabs with five swipes
     * beats hitting a 40dp target five times.
     */
    private fun attachSwipeToClose() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.START or ItemTouchHelper.END,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter.tabAt(viewHolder.bindingAdapterPosition)?.let { tab ->
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
                        binding.tabsList.isVisible = tabs.isNotEmpty()
                        binding.emptyState.isVisible = tabs.isEmpty()
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
        fun intent(context: Context): Intent = Intent(context, TabsActivity::class.java)
    }
}

private class TabsAdapter(
    private val icons: BrowserIcons,
    private val scope: CoroutineScope,
    private val onClick: (TabSessionState) -> Unit,
    private val onClose: (TabSessionState) -> Unit,
) : RecyclerView.Adapter<TabViewHolder>() {

    private var tabs: List<TabSessionState> = emptyList()
    private var selectedId: String? = null

    fun submit(tabs: List<TabSessionState>, selectedId: String?) {
        this.tabs = tabs
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    /** The tab at a list position, or null if the list moved under a gesture. */
    fun tabAt(position: Int): TabSessionState? = tabs.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder =
        TabViewHolder.inflate(parent)

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        holder.bind(
            tab = tab,
            isSelected = tab.id == selectedId,
            icons = icons,
            scope = scope,
            onClick = onClick,
            onClose = onClose,
        )
    }
}
