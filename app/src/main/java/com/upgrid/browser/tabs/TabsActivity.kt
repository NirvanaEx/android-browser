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
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityTabsBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.flow

/**
 * The tab switcher: a two-column grid of preview cards.
 *
 * A screen rather than the bottom sheet it used to be. Selecting or closing
 * tabs only dispatches to the shared [mozilla.components.browser.state.store.BrowserStore];
 * MainActivity renders whatever comes out, so there is no result to hand back
 * and nothing to keep in sync — finishing is enough.
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
        binding.header.btnHeaderAction.isVisible = true
        binding.header.btnHeaderAction.setText(R.string.tabs_tray_close_all)
        binding.header.btnHeaderAction.setOnClickListener {
            components.tabsUseCases.removeAllTabs()
        }

        adapter = TabsAdapter(
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
        binding.tabsList.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.tabsList.adapter = adapter
        // Cards are a fixed size regardless of position, so RecyclerView can
        // skip re-measuring the whole grid on every insert and remove — closing
        // several tabs in a row is visibly janky otherwise.
        binding.tabsList.setHasFixedSize(true)

        binding.btnNewTab.setOnClickListener {
            components.tabsUseCases.addTab(url = MainActivity.HOME_URL, selectTab = true)
            finish()
        }

        observeTabs()
        wireBackPress()
    }

    private fun observeTabs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                components.store.flow()
                    .map { it.tabs to it.selectedTabId }
                    .distinctUntilChanged()
                    .collect { (tabs, selectedId) ->
                        // Previews for tabs closed elsewhere are dead weight in
                        // a cache measured in megabytes.
                        components.tabThumbnails.retainOnly(tabs.map { it.id }.toSet())

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
        /** Two columns fits a readable title on a phone; three truncates it. */
        private const val COLUMNS = 2

        fun intent(context: Context): Intent = Intent(context, TabsActivity::class.java)
    }
}

private class TabsAdapter(
    private val thumbnails: TabThumbnails,
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder =
        TabViewHolder.inflate(parent)

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        holder.bind(
            tab = tab,
            isSelected = tab.id == selectedId,
            thumbnails = thumbnails,
            onClick = onClick,
            onClose = onClose,
        )
    }
}
