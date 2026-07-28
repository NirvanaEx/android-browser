package com.upgrid.browser.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.FragmentHistoryBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browsing history as a bottom sheet, matching the tabs tray's shape.
 *
 * Tapping a row loads that URL in the selected tab and dismisses; the trash
 * icon drops a single entry; "Clear all" wipes the table behind a confirm.
 *
 * Reads go through [HistoryStore]'s suspending API on the fragment's own
 * lifecycle scope, so a slow query can't block the sheet's entry animation.
 */
class HistoryFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val components get() = (requireActivity().application as BrowserApplication).components
    private val store by lazy { HistoryStore(requireContext()) }

    /** In-flight reload; cancelled on each keystroke so searches don't stack. */
    private var reloadJob: Job? = null

    private val adapter by lazy {
        HistoryAdapter(
            onOpen = { entry ->
                components.sessionUseCases.loadUrl(entry.url)
                dismiss()
            },
            onDelete = { entry ->
                viewLifecycleOwner.lifecycleScope.launch {
                    store.delete(entry.id)
                    reload()
                }
            },
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = adapter

        // 250 ms after the last keystroke — long enough that typing a word
        // costs one query instead of one per letter, short enough to feel live.
        binding.searchInput.doAfterTextChanged {
            reloadJob?.cancel()
            reloadJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                reload()
            }
        }

        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        reload()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_clear_all_title)
            .setMessage(R.string.history_clear_all_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.history_clear_all_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    store.clearAll()
                    reload()
                }
            }
            .show()
    }

    private fun reload() {
        reloadJob?.cancel()
        reloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val query = binding.searchInput.text?.toString().orEmpty()
            val entries = store.entries(query)
            adapter.submit(entries)

            binding.historyList.isVisible = entries.isNotEmpty()
            binding.emptyState.isVisible = entries.isEmpty()
            binding.emptyTitle.setText(
                if (query.isBlank()) R.string.history_empty_title
                else R.string.history_empty_search
            )
            // Nothing to wipe on an empty table, but a filtered-to-empty view
            // still has rows behind it — key the button off the table, not the
            // current filter.
            binding.btnClearAll.isVisible = query.isNotBlank() || entries.isNotEmpty()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reloadJob?.cancel()
        _binding = null
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        const val TAG = "history"
    }
}
