package com.upgrid.browser.search

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.BrowserComponents
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemSuggestionBinding
import com.upgrid.browser.prefs.BrowserPreferences
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
 * Builds the omnibar drop-down.
 *
 * Two halves, and the split matters. [local] answers from the device — three
 * SQLite reads, back in a millisecond — and [withRemote] then goes and asks the
 * search engine what the user is probably typing. The caller renders the first
 * immediately and the second when it lands, so the list never waits on the
 * network to appear.
 *
 * Order within the local half is fixed and deliberate: **bookmarks first**,
 * then visited pages, then past search queries. A bookmark is a page the user
 * chose to keep, so it is always the better guess than one they merely passed
 * through — ranking the three sources by a relevance score would let a
 * heavily-refreshed page outrank something deliberately saved. Engine
 * completions go last: they're guesses about the world, the rest are facts
 * about this user.
 *
 * Duplicates are dropped as the list is built, so a bookmarked page that is
 * also in history appears once, as a bookmark.
 */
class SuggestionSource(private val components: BrowserComponents) {

    private val preferences by lazy { BrowserPreferences(components.context) }

    private val remote by lazy {
        SearchSuggestionClient(components.httpClient) { preferences.searchEngine }
    }

    /** Bookmarks, history and past searches. No I/O beyond the local database. */
    suspend fun local(query: String): List<Suggestion> {
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

    /**
     * [local] plus the engine's completions, plus a row that just runs the
     * query as typed.
     *
     * That last row is why the drop-down can never come back empty: on a fresh
     * install with no history and no network there is still one obviously
     * correct thing to offer, which beats a panel that silently doesn't appear.
     * It goes last so it doesn't push the user's own bookmarks down.
     */
    suspend fun withRemote(query: String, local: List<Suggestion>): List<Suggestion> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) return local

        // Comparing on the *text* rather than the URL: a past search and a
        // remote completion for the same words are the same row to the user,
        // even though one is a query and the other is a query too.
        val seen = local.mapTo(HashSet()) { it.title.lowercase() }
        val out = ArrayList(local)

        val engine = preferences.searchEngine
        remote.suggestionsFor(trimmed).asSequence()
            .filter { seen.add(it.lowercase()) }
            // A full local half leaves no room, and Sequence.take throws on a
            // negative count rather than treating it as zero.
            .take((MAX_TOTAL - out.size - 1).coerceAtLeast(0))
            .forEach { completion ->
                out += Suggestion(
                    title = completion,
                    subtitle = "",
                    target = completion,
                    kind = Suggestion.Kind.SEARCH,
                )
            }

        if (seen.add(trimmed.lowercase())) {
            out += Suggestion(
                title = trimmed,
                subtitle = engine.displayName,
                target = trimmed,
                kind = Suggestion.Kind.SEARCH,
            )
        }

        return out
    }

    /** The scheme is identical on every row and only steals width from the path. */
    private fun strip(url: String) =
        url.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private companion object {
        /**
         * One letter is enough now that the engine answers too — the local
         * half over-matches at that length, but it's capped and the remote
         * half is the useful part of a one-letter drop-down anyway.
         */
        const val MIN_QUERY = 1
        const val MAX_BOOKMARKS = 3
        const val MAX_HISTORY = 4
        const val MAX_SEARCHES = 2

        /** About as many rows as fit above the keyboard on a phone. */
        const val MAX_TOTAL = 9

        /** Over-fetch so that dropping bookmark duplicates can't starve the list. */
        const val HISTORY_SCAN = 30
    }
}

/**
 * Flat list of [Suggestion]s under the omnibar.
 *
 * [onFill] is the trailing arrow: it puts the row's text into the address bar
 * instead of loading it, which is how you take a suggestion and then add a word
 * to it. Without it, a suggestion that's *almost* right has to be retyped.
 */
class SuggestionAdapter(
    private val onPick: (Suggestion) -> Unit,
    private val onFill: (Suggestion) -> Unit,
) : RecyclerView.Adapter<SuggestionAdapter.Holder>() {

    private var items: List<Suggestion> = emptyList()

    /** What the user has typed so far, so the matching part can be emphasised. */
    private var query: String = ""

    fun submit(suggestions: List<Suggestion>, typed: String = query) {
        items = suggestions
        query = typed
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Suggestion) = with(binding) {
            suggestionTitle.text = emphasise(item.title, query)
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
            suggestionFill.setOnClickListener { onFill(item) }
        }

        /**
         * Bold the part of the row that matches what's been typed.
         *
         * Which is the useful half of a drop-down: nine rows of identical-
         * looking text force you to read all of them, and the eye is looking
         * for its own words.
         */
        private fun emphasise(text: String, typed: String): CharSequence {
            if (typed.isBlank()) return text
            val at = text.indexOf(typed, ignoreCase = true)
            if (at < 0) return text
            return SpannableString(text).apply {
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    at,
                    at + typed.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }
}
