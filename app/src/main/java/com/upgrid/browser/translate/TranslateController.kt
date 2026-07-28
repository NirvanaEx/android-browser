package com.upgrid.browser.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.view.isVisible
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ViewTranslateBarBinding
import com.upgrid.browser.fullscreen.VideoPlayerBridge
import org.json.JSONObject
import java.util.Locale

/**
 * Page translation.
 *
 * The work happens in translate.js: the page's text is collected, sent to
 * Google's translation endpoint and written back into the nodes it came from.
 * Nothing navigates, so the address stays the address, the session stays logged
 * in, and "show original" is instant.
 *
 * This replaced a redirect through `*.translate.goog`, which brought a captcha
 * (the proxy rate-limits by IP) and Google's own banner across the top of the
 * page in Google's colours. The bar here is the same control in the app's
 * theme: two chips, original and target, with the live one filled.
 *
 * State is per page and deliberately not remembered across navigation — the
 * content script goes away with the document, so anything we remembered would
 * be a claim about a page that no longer exists.
 */
class TranslateController(
    private val binding: ViewTranslateBarBinding,
    private val bridge: VideoPlayerBridge,
) {

    private val context: Context get() = binding.root.context

    /**
     * Watchdog for a translation that never answers.
     *
     * Nothing guarantees a reply: a page can have no content script (a PDF
     * viewer, an error page the engine drew itself), and a spinner that turns
     * forever is worse than an honest failure.
     */
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable {
        if (!working) return@Runnable
        working = false
        render()
        binding.translateStatus.setText(R.string.translate_failed)
    }

    /** Language to translate into: the one the phone is set to. */
    private val target: String = Locale.getDefault().language.ifBlank { "en" }

    /** Detected language of the page, as reported by the endpoint. */
    private var source: String = ""

    private var working = false

    /** True while the page is showing translated text. */
    var isTranslated = false
        private set

    val isOpen: Boolean get() = binding.root.isVisible

    init {
        binding.translateChipOriginal.setOnClickListener { showOriginal() }
        binding.translateChipTarget.setOnClickListener { translate() }
        binding.translateClose.setOnClickListener { close() }
        binding.translateChipTarget.text = displayName(target)
    }

    /** Menu entry: translate, or put the original back if it's already done. */
    fun toggle() {
        if (isTranslated) showOriginal() else translate()
    }

    fun translate() {
        if (working || isTranslated) {
            show()
            return
        }
        working = true
        show()
        render()
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, TIMEOUT_MS)
        bridge.sendPageCommand("translate") { put("to", target) }
    }

    fun showOriginal() {
        if (!isTranslated) {
            render()
            return
        }
        bridge.sendPageCommand("untranslate")
        isTranslated = false
        render()
    }

    /**
     * Hide the bar without touching the page. Leaving a translated page
     * translated is the point — the bar is a control, not the translation.
     */
    fun close() {
        binding.root.isVisible = false
    }

    fun onBackPressed(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    /** A new document: whatever we knew about the old one no longer holds. */
    fun onPageChanged() {
        handler.removeCallbacks(giveUp)
        working = false
        isTranslated = false
        source = ""
        close()
    }

    /** One `{t:"translate", state, source}` report from translate.js. */
    fun render(event: JSONObject) {
        source = event.optString("source").ifBlank { source }
        if (event.optString("state") != "working") handler.removeCallbacks(giveUp)
        when (event.optString("state")) {
            "working" -> working = true
            "done" -> {
                working = false
                isTranslated = true
            }
            "idle" -> {
                working = false
                isTranslated = false
            }
            "empty", "error" -> {
                working = false
                isTranslated = false
            }
        }
        if (event.optString("state") == "error" || event.optString("state") == "empty") {
            binding.translateStatus.setText(R.string.translate_failed)
            binding.translateSpinner.isVisible = false
            binding.translateIcon.isVisible = true
            binding.translateChipOriginal.isSelected = true
            binding.translateChipTarget.isSelected = false
            return
        }
        render()
    }

    private fun show() {
        binding.root.isVisible = true
    }

    private fun render() {
        binding.translateSpinner.isVisible = working
        binding.translateIcon.isVisible = !working
        binding.translateChipOriginal.isSelected = !isTranslated
        binding.translateChipTarget.isSelected = isTranslated
        binding.translateStatus.text = when {
            working -> context.getString(R.string.translate_working)
            source.isNotBlank() -> context.getString(R.string.translate_from, displayName(source))
            else -> context.getString(R.string.translate_title)
        }
    }

    /** "ru" → "Русский", in the user's own language. */
    private fun displayName(code: String): String {
        val name = Locale(code).getDisplayLanguage(Locale.getDefault())
        if (name.isBlank()) return code.uppercase(Locale.ROOT)
        return name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private companion object {
        /** A long article is a dozen round-trips; this is well past that. */
        const val TIMEOUT_MS = 30_000L
    }
}
