package com.upgrid.browser.search

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.BrowserComponents
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemSuggestionBinding
import com.upgrid.browser.ui.HostTile

/**
 * One row in the omnibar drop-down.
 *
 * [target] is what to load or search for; [title] and [subtitle] are what the
 * row reads as.
 */
data class Suggestion(
    val title: String,
    val subtitle: String,
    val target: String,
    val kind: Kind,
) {
    enum class Kind { BOOKMARK, HISTORY, SEARCH }
}

/**
 * Builds omnibar suggestions from what the user has already done.
 *
 * Order is fixed and deliberate: **bookmarks first**, then visited pages, then
 * past search queries. A bookmark is a page the user chose to keep, so it is
 * always the better guess than a page they merely passed through — ranking the
 * three sources by relevance score would let a heavily-refreshed page outrank
 * something deliberately saved.
 *
 * Duplicates are dropped by URL as the list is built, so a bookmarked page that
 * is also in history appears once, as a bookmark.
 */
class SuggestionSource(private val components: BrowserComponents) {

    suspend fun forQuery(query: String): List<Suggestion> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) return emptyList()

        val seen = HashSet<String>()
        val out = ArrayList<Suggestion>(MAX_TOTAL)

        components.bookmarks.all(trimmed).asSequence()
            .take(MAX_BOOKMARKS)
            .forEach { bookmark ->
                if (seen.add(bookmark.url)) {
                    out += Suggestion(
                        title = bookmark.title.ifBlank { bookmark.host.ifBlank { bookmark.url } },
                        subtitle = strip(bookmark.url),
                        target = bookmark.url,
                        kind = Suggestion.Kind.BOOKMARK,
                    )
                }
            }

        components.browsingHistory.entries(trimmed, limit = HISTORY_SCAN).asSequence()
            .filter { it.url !in seen }
            .take(MAX_HISTORY)
            .forEach { entry ->
                seen += entry.url
                out += Suggestion(
                    title = entry.title.ifBlank { entry.host.ifBlank { entry.url } },
                    subtitle = strip(entry.url),
                    target = entry.url,
                    kind = Suggestion.Kind.HISTORY,
                )
            }

        components.searchHistory.recent().asSequence()
            .filter { it.contains(trimmed, ignoreCase = true) && !it.equals(trimmed, true) }
            .take(MAX_SEARCHES)
            .forEach { past ->
                out += Suggestion(
                    title = past,
                    subtitle = "",
                    target = past,
                    kind = Suggestion.Kind.SEARCH,
                )
            }

        return out
    }

    /** The scheme is identical on every row and only steals width from the path. */
    private fun strip(url: String) =
        url.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private companion object {
        /** One character matches most of the table and reads as noise. */
        const val MIN_QUERY = 2
        const val MAX_BOOKMARKS = 4
        const val MAX_HISTORY = 6
        const val MAX_SEARCHES = 3
        const val MAX_TOTAL = MAX_BOOKMARKS + MAX_HISTORY + MAX_SEARCHES

        /** Over-fetch so that dropping bookmark duplicates can't starve the list. */
        const val HISTORY_SCAN = 30
    }
}

/** Flat list of [Suggestion]s under the omnibar. */
class SuggestionAdapter(
    private val onPick: (Suggestion) -> Unit,
) : RecyclerView.Adapter<SuggestionAdapter.Holder>() {

    private var items: List<Suggestion> = emptyList()

    fun submit(suggestions: List<Suggestion>) {
        items = suggestions
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Suggestion) = with(binding) {
            suggestionTitle.text = item.title
            suggestionUrl.text = item.subtitle
            suggestionUrl.visibility =
                if (item.subtitle.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

            suggestionIcon.setImageResource(
                when (item.kind) {
                    Suggestion.Kind.BOOKMARK -> R.drawable.ic_bookmark_filled
                    Suggestion.Kind.HISTORY -> R.drawable.ic_history
                    Suggestion.Kind.SEARCH -> R.drawable.ic_find
                }
            )
            // A bookmark row is tinted with the site's own colour so the saved
            // ones stand out from the history below them without a second label.
            suggestionIcon.imageTintList = ColorStateList.valueOf(
                when (item.kind) {
                    Suggestion.Kind.BOOKMARK -> HostTile.colorFor(HostTile.hostOf(item.target))
                    else -> com.google.android.material.color.MaterialColors.getColor(
                        suggestionIcon,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    )
                }
            )

            root.setOnClickListener { onPick(item) }
        }
    }
}
