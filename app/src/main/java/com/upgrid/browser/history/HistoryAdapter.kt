package com.upgrid.browser.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.databinding.ItemHistoryHeaderBinding
import com.upgrid.browser.databinding.ItemHistoryRowBinding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Flat list of day headers + visited pages.
 *
 * The grouping is precomputed into [Row]s by [submit] rather than resolved per
 * bind: `onBindViewHolder` would otherwise have to look backwards at the
 * previous entry to decide whether a header belongs here, which breaks as soon
 * as the list is filtered.
 */
class HistoryAdapter(
    private val todayLabel: String,
    private val yesterdayLabel: String,
    private val onOpen: (HistoryEntry) -> Unit,
    private val onDelete: (HistoryEntry) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val label: String) : Row
        data class Item(val entry: HistoryEntry) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(entries: List<HistoryEntry>) {
        val today = LocalDate.now()
        val built = mutableListOf<Row>()
        var currentDay: LocalDate? = null
        entries.forEach { entry ->
            val day = Instant.ofEpochMilli(entry.visitedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            if (day != currentDay) {
                currentDay = day
                built += Row.Header(labelFor(day, today))
            }
            built += Row.Item(entry)
        }
        rows = built
        notifyDataSetChanged()
    }

    private fun labelFor(day: LocalDate, today: LocalDate): String = when (day) {
        today -> todayLabel
        today.minusDays(1) -> yesterdayLabel
        else -> day.format(DAY_FORMAT)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryHolder(ItemHistoryRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.label)
            is Row.Item -> (holder as EntryHolder).bind(row.entry)
        }
    }

    private class HeaderHolder(private val binding: ItemHistoryHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            binding.headerLabel.text = label
        }
    }

    private inner class EntryHolder(private val binding: ItemHistoryRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: HistoryEntry) = with(binding) {
            // Pages often have no <title> until late (or at all); the host
            // reads better than a raw URL as a fallback headline.
            historyTitle.text = entry.title.ifBlank { entry.host.ifBlank { entry.url } }
            historyUrl.text = entry.url
            historyTime.text = Instant.ofEpochMilli(entry.visitedAt)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMAT)
            historyInitial.text = (entry.host.firstOrNull() ?: '?').uppercase()

            root.setOnClickListener { onOpen(entry) }
            btnDeleteEntry.setOnClickListener { onDelete(entry) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
        val DAY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    }
}
