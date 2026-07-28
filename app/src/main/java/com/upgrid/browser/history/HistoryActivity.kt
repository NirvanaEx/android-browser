package com.upgrid.browser.history

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browsing history: day-grouped list with a filter field.
 *
 * A screen rather than the bottom sheet it used to be. A sheet gave the list
 * whatever height was left over and put a drag handle where a back arrow
 * belongs — history is somewhere you go and search through, not something you
 * peek at.
 *
 * Tapping a row loads it in the selected tab and returns; the trash icon drops
 * one entry; the header action wipes the table behind a confirm.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val components get() = (application as BrowserApplication).components
    private val store get() = components.browsingHistory

    /** In-flight reload; cancelled on each keystroke so searches don't stack. */
    private var reloadJob: Job? = null

    private val adapter by lazy {
        HistoryAdapter(
            todayLabel = getString(R.string.history_today),
            yesterdayLabel = getString(R.string.history_yesterday),
            onOpen = { entry ->
                components.sessionUseCases.loadUrl(entry.url)
                finish()
            },
            onDelete = { entry ->
                lifecycleScope.launch {
                    store.delete(entry.id)
                    reload()
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.pageTitle.setText(R.string.history_title)
        binding.header.btnBack.setOnClickListener { finish() }
        binding.header.btnHeaderAction.setText(R.string.history_clear_all)
        binding.header.btnHeaderAction.setOnClickListener { confirmClearAll() }

        binding.search.searchInput.setHint(R.string.history_search_hint)
        // 250 ms after the last keystroke — long enough that typing a word costs
        // one query instead of one per letter, short enough to feel live.
        binding.search.searchInput.doAfterTextChanged {
            reloadJob?.cancel()
            reloadJob = lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                render()
            }
        }

        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.historyList.setHasFixedSize(true)

        reload()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear_all_title)
            .setMessage(R.string.history_clear_all_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.history_clear_all_confirm) { _, _ ->
                lifecycleScope.launch {
                    store.clearAll()
                    reload()
                }
            }
            .show()
    }

    /** Cancel any pending search and redraw now. Safe from click handlers. */
    private fun reload() {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch { render() }
    }

    /**
     * Query and draw. Kept separate from [reload] because the debounce
     * coroutine calls it directly — going through [reload] would have that
     * coroutine cancel its own job on the way in.
     */
    private suspend fun render() {
        val query = binding.search.searchInput.text?.toString().orEmpty()
        val entries = store.entries(query)
        adapter.submit(entries)

        binding.historyList.isVisible = entries.isNotEmpty()
        binding.emptyState.isVisible = entries.isEmpty()
        binding.emptyTitle.setText(
            if (query.isBlank()) R.string.history_empty_title
            else R.string.history_empty_search
        )
        // A filtered-to-empty view still has rows behind it, so key the button
        // off the table rather than the visible list — but hide it once the
        // table is genuinely empty, including right after a clear-all.
        binding.header.btnHeaderAction.isVisible = store.count() > 0
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L

        fun intent(context: Context): Intent = Intent(context, HistoryActivity::class.java)
    }
}
