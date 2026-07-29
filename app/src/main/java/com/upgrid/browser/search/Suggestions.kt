package com.upgrid.browser.search

import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.upgrid.browser.BrowserComponents
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ItemSuggestionBinding
import com.upgrid.browser.prefs.BrowserPreferences
import com.upgrid.browser.ui.HostTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.icons.BrowserIcons

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
    /**
     * Whether a long press can make this row go away for good.
     *
     * True only for rows that came from this device's own record — a visited
     * page or a query typed before. The search engine's guesses aren't ours to
     * delete, the row that runs what you just typed isn't stored anywhere, and
     * a bookmark disappearing from a long press in the address bar is not
     * what anybody means by "forget this".
     */
    val removable: Boolean = false,
) {
    enum class Kind { BOOKMARK, HISTORY, SEARCH }
}

/**
 * Builds the omnibar drop-down.
 *
 * Two halves that run **at the same time**, which is the whole shape of this
 * class. [local] answers from the device — three SQLite reads, back in a
 * millisecond — and [completions] asks the search engine what the user is
 * probably typing. The caller fires both, renders the first the moment it
 * lands, and folds the second in when it arrives, so the drop-down never waits
 * on the network to appear and never loses the engine's half to a slow
 * database.
 *
 * They used to run in sequence, remote after local, which meant the network
 * request didn't even start until three queries had finished on the main
 * thread — and every one of those milliseconds came out of the window before
 * the next keystroke cancelled the whole thing.
 *
 * Order within the local half is fixed and deliberate: **bookmarks first**,
 * then visited pages, then past search queries. A bookmark is a page the user
 * chose to keep, so it is always the better guess than one they merely passed
 * through — ranking the three sources by a relevance score would let a
 * heavily-refreshed page outrank something deliberately saved.
 *
 * Duplicates are dropped as the list is built — see [Dedup] for what counts as
 * one, which is more than "same string".
 */
class SuggestionSource(private val components: BrowserComponents) {

    private val preferences by lazy { BrowserPreferences(components.context) }

    private val remote by lazy {
        SearchSuggestionClient(components.httpClient) { preferences.searchEngine }
    }

    /**
     * Bookmarks, history, past searches — and the row that just runs the query.
     *
     * All of it on [Dispatchers.IO]. These are three SQLite reads and they were
     * being made from a coroutine started on the main thread, i.e. on the main
     * thread, on every keystroke, while the engine was laying out a page.
     */
    suspend fun local(query: String): List<Suggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) return@withContext emptyList<Suggestion>()

        val seen = Dedup()
        val out = ArrayList<Suggestion>(MAX_TOTAL)

        // The search row goes first when what's typed reads as words rather
        // than as an address: that is what the user is about to do, and having
        // to scroll past four half-remembered pages to reach it is how a
        // drop-down stops being worth opening. For a hostname it goes last —
        // there the address itself is the answer.
        val asTyped = searchRow(trimmed)
        if (looksLikeQuery(trimmed)) {
            seen.add(asTyped)
            out += asTyped
        }

        components.bookmarks.all(trimmed).asSequence()
            .map { bookmark ->
                Suggestion(
                    title = bookmark.title.ifBlank { bookmark.host.ifBlank { bookmark.url } },
                    subtitle = strip(bookmark.url),
                    target = bookmark.url,
                    kind = Suggestion.Kind.BOOKMARK,
                )
            }
            .filter(seen::add)
            .take(MAX_BOOKMARKS)
            .forEach { out += it }

        components.browsingHistory.entries(trimmed, limit = HISTORY_SCAN).asSequence()
            .map { entry ->
                Suggestion(
                    title = entry.title.ifBlank { entry.host.ifBlank { entry.url } },
                    subtitle = strip(entry.url),
                    target = entry.url,
                    kind = Suggestion.Kind.HISTORY,
                    removable = true,
                )
            }
            .filter(seen::add)
            .take(MAX_HISTORY)
            .forEach { out += it }

        components.searchHistory.recent().asSequence()
            .filter { it.contains(trimmed, ignoreCase = true) && !it.equals(trimmed, true) }
            .map { past ->
                Suggestion(
                    title = past,
                    subtitle = "",
                    target = past,
                    kind = Suggestion.Kind.SEARCH,
                    removable = true,
                )
            }
            .filter(seen::add)
            .take(MAX_SEARCHES)
            .forEach { out += it }

        if (seen.add(asTyped)) out += asTyped

        out
    }

    /**
     * What the search engine thinks the user is typing. Empty when it doesn't
     * answer in time — see [SearchSuggestionClient].
     */
    suspend fun completions(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) return emptyList()
        return remote.suggestionsFor(trimmed)
    }

    /**
     * Fold the engine's [completions] into what [local] already produced.
     *
     * The engine's guesses go under the user's own history rather than over it:
     * they are guesses about the world, the rest are facts about this user. The
     * one exception is the row that runs the query as typed, which [local]
     * already placed.
     */
    fun merge(local: List<Suggestion>, completions: List<String>): List<Suggestion> {
        if (completions.isEmpty()) return local

        // Re-seed from the local half rather than tracking state across the two
        // calls: they are separate queries and the second one may have been
        // built against a different first.
        val seen = Dedup().apply { local.forEach { add(it) } }
        val out = ArrayList(local)

        completions.asSequence()
            .map { completion ->
                Suggestion(
                    title = completion,
                    subtitle = "",
                    target = completion,
                    kind = Suggestion.Kind.SEARCH,
                )
            }
            .filter(seen::add)
            // A full local half leaves no room, and Sequence.take throws on a
            // negative count rather than treating it as zero.
            .take((MAX_TOTAL - out.size).coerceAtLeast(0))
            .forEach { out += it }

        return out
    }

    private fun searchRow(query: String) = Suggestion(
        title = query,
        subtitle = preferences.searchEngine.displayName,
        target = query,
        kind = Suggestion.Kind.SEARCH,
    )

    /**
     * True when the text reads as words rather than as an address.
     *
     * Same rule MainActivity applies when deciding what a committed input
     * means, and it has to be the same one: a drop-down that offers to search
     * for something the address bar would have loaded is offering the wrong
     * thing.
     */
    private fun looksLikeQuery(input: String): Boolean {
        if (runCatching { Uri.parse(input).scheme }.getOrNull() != null) return false
        return input.contains(' ') || !input.contains('.')
    }

    /** The scheme is identical on every row and only steals width from the path. */
    private fun strip(url: String) =
        url.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    /**
     * Decides whether a row is already in the list.
     *
     * Two questions, because there are two ways to be a duplicate:
     *
     *  - **Same destination.** `https://ya.ru`, `http://www.ya.ru/` and
     *    `ya.ru/#top` are one page. Bookmarks are saved with whatever URL the
     *    address bar held, history records what the page settled on, and the
     *    two differ by a slash more often than not — which is how the same site
     *    ended up listed twice.
     *  - **Same row.** Even when the URLs genuinely differ, two rows from one
     *    host with the same title read as one thing repeated: a feed and its
     *    front page are both "Хабр", and nine slots shouldn't hold three of
     *    them. The first one wins, and it's the higher-ranked source.
     */
    private class Dedup {
        private val targets = HashSet<String>()
        private val labels = HashSet<String>()

        fun add(suggestion: Suggestion): Boolean {
            if (!targets.add(normalise(suggestion.target))) return false
            val label = suggestion.title.trim().lowercase() +
                ' ' + HostTile.hostOf(suggestion.target)
            return labels.add(label)
        }

        private fun normalise(target: String): String = target.trim().lowercase()
            .substringBefore('#')
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removeSuffix("/")
    }

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
    /** Favicon loader: a saved page is recognised by its icon, not by a letter. */
    private val icons: BrowserIcons,
    /** Host's lifecycle scope — icon fetches must die with the screen. */
    private val scope: CoroutineScope,
    private val onPick: (Suggestion) -> Unit,
    private val onFill: (Suggestion) -> Unit,
    /** Long press on a row this device put there: offer to forget it. */
    private val onForget: (Suggestion) -> Unit,
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

            // A destination gets the site's own face — favicon over the letter
            // tile, exactly as in history and bookmarks. A query has no site to
            // show, so it gets the magnifier.
            when (item.kind) {
                Suggestion.Kind.SEARCH -> suggestionIcon.bindGlyph(R.drawable.ic_find)
                else -> suggestionIcon.bindSite(item.target, icons, scope)
            }
            // The star marks the rows that are saved, which is the one thing
            // the icon alone can no longer say.
            suggestionBadge.isVisible = item.kind == Suggestion.Kind.BOOKMARK

            root.setOnClickListener { onPick(item) }
            suggestionFill.setOnClickListener { onFill(item) }

            // Long press forgets the row — but only where there is something to
            // forget. `isLongClickable` has to be cleared by hand on the other
            // rows: setting a listener turns it on, and setting it back to null
            // does not turn it off, so a recycled row would keep swallowing
            // long presses and doing nothing with them.
            if (item.removable) {
                root.setOnLongClickListener { onForget(item); true }
            } else {
                root.setOnLongClickListener(null)
                root.isLongClickable = false
            }
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
