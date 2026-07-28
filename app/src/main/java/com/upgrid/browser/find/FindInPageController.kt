package com.upgrid.browser.find

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ViewFindBarBinding
import com.upgrid.browser.fullscreen.VideoPlayerBridge
import org.json.JSONObject

/**
 * Find in page.
 *
 * The bar is ours and so is the search: the matching, the highlighting and the
 * scrolling all happen in the page, in find.js. This class is only the chrome
 * — it sends verbs down and renders the counter that comes back.
 *
 * Why not mozilla's FindInPageBar and the engine's finder: the engine paints
 * matches with an internal selection colour no CSS can reach (so "orange like
 * Chrome" is impossible), and it skips subtrees the page never laid out (so
 * words visible further down a feed came back as not found). Both are argued
 * in full at the top of find.js.
 */
class FindInPageController(
    private val binding: ViewFindBarBinding,
    private val bridge: VideoPlayerBridge,
) {

    private val context: Context get() = binding.root.context
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** Last query actually sent, so a redundant keystroke doesn't re-search. */
    private var sent: String? = null

    val isOpen: Boolean get() = binding.root.isVisible

    init {
        binding.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty())
        })

        // The IME's search key means "next", not "search again": the results
        // are already on screen by the time the user reaches for it.
        binding.findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                step(next = true)
                true
            } else {
                false
            }
        }

        binding.findNext.setOnClickListener { step(next = true) }
        binding.findPrev.setOnClickListener { step(next = false) }
        binding.findClose.setOnClickListener { close() }
    }

    /** Show the bar, put the cursor in it and raise the keyboard. */
    fun open() {
        binding.root.isVisible = true
        binding.findCount.isVisible = false
        binding.findInput.setText("")
        sent = null
        binding.findInput.requestFocus()
        // stateAlwaysHidden on the window governs only the *initial* IME state,
        // so asking for it explicitly still works — and has to be asked for: a
        // search bar you must tap before you can type into isn't finished.
        imm().showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hide the bar and take the highlights off the page. */
    fun close() {
        if (!isOpen) return
        cancelPending()
        bridge.sendPageCommand("findClear")
        binding.root.isVisible = false
        binding.findInput.setText("")
        binding.findCount.isVisible = false
        sent = null
        hideKeyboard()
    }

    /** True if this consumed the back press. */
    fun onBackPressed(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    /**
     * A navigation throws away the page and with it every highlight; the bar
     * would otherwise keep showing a count for a document that no longer
     * exists.
     */
    fun onPageChanged() {
        if (!isOpen) return
        sent = null
        binding.findCount.isVisible = false
        // Longer than the typing debounce on purpose: the new document's
        // content script is injected at document_idle, which is after the
        // store has already told us the URL changed. Searching sooner would
        // shout into an empty room.
        schedule(binding.findInput.text?.toString().orEmpty(), PAGE_SETTLE_MS)
    }

    /** One `{t:"find", total, index}` report from find.js. */
    fun render(event: JSONObject) {
        val total = event.optInt("total", 0)
        val index = event.optInt("index", 0)
        val query = event.optString("query")
        if (query.isEmpty()) {
            binding.findCount.isVisible = false
            return
        }
        binding.findCount.isVisible = true
        binding.findCount.text = if (total == 0) {
            context.getString(R.string.find_no_results)
        } else {
            context.getString(R.string.find_counter, index, total)
        }
        // Nothing to step through: saying so with the buttons is quieter than
        // a toast and truer than leaving them looking live.
        val enabled = total > 1
        binding.findNext.isEnabled = enabled
        binding.findPrev.isEnabled = enabled
        binding.findNext.alpha = if (enabled) 1f else DISABLED_ALPHA
        binding.findPrev.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun schedule(query: String, delay: Long = DEBOUNCE_MS) {
        cancelPending()
        // One search per word typed rather than one per letter. The work is a
        // full DOM walk in the page, so this is the difference between typing
        // smoothly and typing into treacle.
        val run = Runnable {
            if (query == sent) return@Runnable
            sent = query
            if (query.isEmpty()) {
                bridge.sendPageCommand("findClear")
                binding.findCount.isVisible = false
            } else {
                bridge.sendPageCommand("find") { put("query", query) }
            }
        }
        pending = run
        handler.postDelayed(run, delay)
    }

    private fun step(next: Boolean) {
        // Flush a pending search first, or "next" steps through the previous
        // query's matches.
        pending?.let { handler.removeCallbacks(it); it.run() }
        pending = null
        bridge.sendPageCommand(if (next) "findNext" else "findPrev")
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun imm() =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun hideKeyboard() {
        imm().hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private companion object {
        const val DEBOUNCE_MS = 220L
        const val PAGE_SETTLE_MS = 700L
        const val DISABLED_ALPHA = 0.35f
    }
}
