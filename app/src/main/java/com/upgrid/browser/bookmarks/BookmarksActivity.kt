package com.upgrid.browser.bookmarks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityBookmarksBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab

/**
 * Saved pages, with a filter field. Same skeleton as [com.upgrid.browser.history.HistoryActivity]
 * on purpose — they're sibling destinations in the menu and there's nothing
 * about a saved page that warrants a different layout from a visited one.
 *
 * Deleting shows an undo snackbar rather than a confirm dialog: a mis-tap next
 * to a row you meant to open shouldn't silently cost a bookmark, and a dialog
 * per delete would make clearing several unbearable.
 */
class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarksBinding
    private val components get() = (application as BrowserApplication).components
    private val store get() = components.bookmarks

    /** In-flight reload; cancelled on each keystroke so searches don't stack. */
    private var reloadJob: Job? = null

    private val adapter by lazy {
        BookmarkAdapter(
            onOpen = { bookmark ->
                components.sessionUseCases.loadUrl(bookmark.url)
                finish()
            },
            onDelete = { bookmark -> deleteWithUndo(bookmark) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.bookmarks_title)
        binding.header.btnBack.setOnClickListener { finish() }
        binding.header.btnHeaderAction.isVisible = true
        binding.header.btnHeaderAction.setText(R.string.bookmarks_add_current)
        binding.header.btnHeaderAction.setOnClickListener { addCurrentPage() }

        binding.search.searchInput.setHint(R.string.bookmarks_search_hint)
        binding.search.searchInput.doAfterTextChanged {
            reloadJob?.cancel()
            reloadJob = lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                render()
            }
        }

        binding.bookmarksList.layoutManager = LinearLayoutManager(this)
        binding.bookmarksList.adapter = adapter
        binding.bookmarksList.setHasFixedSize(true)

        reload()
    }

    /** Save the page open in the selected tab, if it's a real one. */
    private fun addCurrentPage() {
        val tab = components.store.state.selectedTab ?: return
        val url = tab.content.url
        if (!BookmarkStore.isBookmarkable(url)) {
            Toast.makeText(this, R.string.bookmark_not_saveable, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            // Already saved → say so and leave the list alone. Toggling it off
            // from a button labelled "add" would be a trap.
            val saved = store.isBookmarked(url) || store.toggle(url, tab.content.title)
            Toast.makeText(
                this@BookmarksActivity,
                if (saved) R.string.bookmark_added else R.string.bookmark_removed,
                Toast.LENGTH_SHORT,
            ).show()
            reload()
        }
    }

    private fun deleteWithUndo(bookmark: Bookmark) {
        lifecycleScope.launch {
            store.delete(bookmark.id)
            reload()
            Snackbar.make(binding.root, R.string.bookmark_removed, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) {
                    lifecycleScope.launch {
                        // Restored through mergeIn rather than toggle so the
                        // original createdAt survives and the row lands back
                        // where it was instead of at the top.
                        store.mergeIn(listOf(bookmark))
                        reload()
                    }
                }
                .show()
        }
    }

    /** Cancel any pending search and redraw now. Safe from click handlers. */
    private fun reload() {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch { render() }
    }

    /**
     * Query and draw. Separate from [reload] because the debounce coroutine
     * calls it directly — going through [reload] would have that coroutine
     * cancel its own job on the way in.
     */
    private suspend fun render() {
        val query = binding.search.searchInput.text?.toString().orEmpty()
        val items = store.all(query)
        adapter.submit(items)

        binding.bookmarksList.isVisible = items.isNotEmpty()
        binding.emptyState.isVisible = items.isEmpty()
        binding.emptyTitle.setText(
            if (query.isBlank()) R.string.bookmarks_empty_title
            else R.string.bookmarks_empty_search
        )
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L

        fun intent(context: Context): Intent = Intent(context, BookmarksActivity::class.java)
    }
}
