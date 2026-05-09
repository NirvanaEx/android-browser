package com.upgrid.browser.tabs

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.FragmentTabsTrayBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.flow

/**
 * Bottom-sheet tabs tray.
 *
 * Hand-rolled adapter rather than `browser-tabstray`'s built-in views — those
 * pull in Compose dependencies and a richer model than we need for MVP.
 * Favicons are read straight off the tab state in the holder; see
 * [TabViewHolder] for the rationale.
 */
class TabsTrayFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentTabsTrayBinding? = null
    private val binding get() = _binding!!

    private val components get() = (requireActivity().application as BrowserApplication).components

    private lateinit var adapter: TabsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTabsTrayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = TabsAdapter(
            onClick = { tab ->
                components.tabsUseCases.selectTab(tab.id)
                dismiss()
            },
            onClose = { tab -> components.tabsUseCases.removeTab(tab.id) }
        )
        binding.tabsList.layoutManager = LinearLayoutManager(requireContext())
        binding.tabsList.adapter = adapter

        binding.btnNewTab.setOnClickListener {
            components.tabsUseCases.addTab(url = MainActivity.HOME_URL, selectTab = true)
            dismiss()
        }
        binding.btnCloseAll.setOnClickListener {
            // removeAllTabs() dispatches a single action; the empty-state branch
            // below dismisses the sheet on the resulting store update.
            components.tabsUseCases.removeAllTabs()
        }

        // React to store changes — update title count and swap to the empty
        // illustration when the list goes to zero. We do *not* auto-dismiss the
        // sheet here; the user sees the empty state, and onDismiss below makes
        // sure MainActivity gets a fresh tab if they swipe the sheet away.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                components.store.flow()
                    .map { it.tabs to it.selectedTabId }
                    .distinctUntilChanged()
                    .collect { (tabs, selectedId) ->
                        adapter.submit(tabs, selectedId)
                        binding.tabsTitle.text = if (tabs.isEmpty())
                            getString(R.string.tabs_tray_title)
                        else
                            getString(R.string.tabs_tray_title_count, tabs.size)
                        binding.btnCloseAll.isEnabled = tabs.isNotEmpty()
                        binding.tabsList.isVisible = tabs.isNotEmpty()
                        binding.emptyState.isVisible = tabs.isEmpty()
                    }
            }
        }
    }

    /**
     * Sheet was swiped/back-pressed away. If the user reached the empty state
     * and dismissed the sheet, MainActivity would otherwise be left with zero
     * tabs and an unrendered engine view; drop them on the start page instead.
     * This is also what Banana does — closing-all-from-tray never leaves the
     * app in a broken visual state.
     */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (components.store.state.tabs.isEmpty()) {
            components.tabsUseCases.addTab(url = MainActivity.HOME_URL, selectTab = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class TabsAdapter(
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
        holder.bind(tab, isSelected = tab.id == selectedId, onClick = onClick, onClose = onClose)
    }
}
