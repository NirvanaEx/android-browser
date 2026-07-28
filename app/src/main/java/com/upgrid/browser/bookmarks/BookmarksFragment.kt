package com.upgrid.browser.bookmarks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.MainActivity
import com.upgrid.browser.R
import com.upgrid.browser.databinding.FragmentBookmarksBinding
import com.upgrid.browser.ui.ExpandedBottomSheetFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab

/**
 * Saved pages, as a bottom sheet matching the history one.
 *
 * Tapping a row loads it in the selected tab and dismisses; the trash icon
 * removes one entry behind an undo snackbar — a mis-tap next to a row you meant
 * to open shouldn't silently cost you a bookmark, and a confirmation dialog per
 * delete would make clearing several unbearable.
 */
class BookmarksFragment : ExpandedBottomSheetFragment() {

    private var _binding: FragmentBookmarksBinding? = null
    private val binding get() = _binding!!

    private val components get() = (requireActivity().application as BrowserApplication).components
    private val store by lazy { BookmarkStore(requireContext()) }

    /** In-flight reload; cancelled on each keystroke so searches don't stack. */
    private var reloadJob: Job? = null

    private val adapter by lazy {
        BookmarkAdapter(
            onOpen = { bookmark ->
                components.sessionUseCases.loadUrl(bookmark.url)
                dismiss()
            },
            onDelete = { bookmark -> deleteWithUndo(bookmark) },
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBookmarksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bookmarksList.layoutManager = LinearLayoutManager(requireContext())
        binding.bookmarksList.adapter = adapter

        binding.searchInput.doAfterTextChanged {
            reloadJob?.cancel()
            reloadJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                render()
            }
        }

        binding.btnAddCurrent.setOnClickListener { addCurrentPage() }

        reload()
    }

    /** Save the page open behind the sheet, if it's a real one. */
    private fun addCurrentPage() {
        val tab = components.store.state.selectedTab ?: return
        val url = tab.content.url
        if (!BookmarkStore.isBookmarkable(url)) {
            Toast.makeText(requireContext(), R.string.bookmark_not_saveable, Toast.LENGTH_SHORT)
                .show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = if (store.isBookmarked(url)) {
                // Already there — don't toggle it off from a button labelled
                // "add", just say so and leave the list alone.
                true
            } else {
                store.toggle(url, tab.content.title)
            }
            Toast.makeText(
                requireContext(),
                if (saved) R.string.bookmark_added else R.string.bookmark_removed,
                Toast.LENGTH_SHORT,
            ).show()
            reload()
            notifyHost()
        }
    }

    private fun deleteWithUndo(bookmark: Bookmark) {
        viewLifecycleOwner.lifecycleScope.launch {
            store.delete(bookmark.id)
            reload()
            notifyHost()
            Snackbar.make(binding.root, R.string.bookmark_removed, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        // Re-inserted through mergeIn rather than toggle so the
                        // original createdAt survives and the row lands back in
                        // the same place in the list, not at the top.
                        store.mergeIn(listOf(bookmark))
                        reload()
                        notifyHost()
                    }
                }
                .show()
        }
    }

    /** Cancel any pending search and redraw now. Safe from click handlers. */
    private fun reload() {
        reloadJob?.cancel()
        reloadJob = viewLifecycleOwner.lifecycleScope.launch { render() }
    }

    /**
     * Query and draw. Separate from [reload] because the debounce coroutine
     * calls it directly — going through [reload] would have that coroutine
     * cancel its own job on the way in.
     */
    private suspend fun render() {
        val query = binding.searchInput.text?.toString().orEmpty()
        val items = store.all(query)
        adapter.submit(items)

        binding.bookmarksList.isVisible = items.isNotEmpty()
        binding.emptyState.isVisible = items.isEmpty()
        binding.emptyTitle.setText(
            if (query.isBlank()) R.string.bookmarks_empty_title
            else R.string.bookmarks_empty_search
        )
    }

    /** The speed dial is backed by bookmarks, so it has to be redrawn. */
    private fun notifyHost() {
        (activity as? MainActivity)?.refreshStartPage()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reloadJob?.cancel()
        _binding = null
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        const val TAG = "bookmarks"
    }
}
