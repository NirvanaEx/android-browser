package com.upgrid.browser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.upgrid.browser.R
import com.upgrid.browser.databinding.ViewSiteIconBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.icons.Icon
import mozilla.components.browser.icons.IconRequest

/**
 * The leading square in every list of sites: history, bookmarks, tabs, the
 * omnibar drop-down.
 *
 * One view rather than four copies of the same FrameLayout, because the point
 * is that a site looks identical wherever it is listed — and because the
 * favicon half is the fiddly part: it is asynchronous, it has to survive the
 * row being recycled under a slow fetch, and it must not blink the letter away
 * when it fails.
 *
 * [BrowserIcons.loadIntoView] is deliberately not used: in a-c 150 it nulls the
 * ImageView before its fetch starts, which flashes an empty square for every
 * row whose icon isn't in memory yet — i.e. all of them, on first scroll.
 */
class SiteIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewSiteIconBinding.inflate(LayoutInflater.from(context), this)

    /** In-flight favicon fetch for the row currently bound here. */
    private var job: Job? = null

    /** URL the in-flight fetch belongs to; a recycled row invalidates it. */
    private var boundUrl: String? = null

    private val faviconPadding = (FAVICON_PADDING_DP * resources.displayMetrics.density).toInt()
    private val glyphPadding = (GLYPH_PADDING_DP * resources.displayMetrics.density).toInt()

    init {
        background = ContextCompat.getDrawable(context, R.drawable.bg_host_tile)
    }

    /**
     * Show a site: coloured letter now, real favicon when it arrives.
     *
     * [icons] caches in memory and on disk, so a list of thirty rows from ten
     * sites costs ten network requests once and none afterwards.
     */
    fun bindSite(url: String, icons: BrowserIcons, scope: CoroutineScope) {
        val host = HostTile.hostOf(url)
        showLetter(host)
        boundUrl = url
        if (url.isBlank()) return

        job = scope.launch {
            val icon = runCatching {
                icons.loadIcon(IconRequest(url = url)).await()
            }.getOrNull() ?: return@launch
            // GENERATOR means BrowserIcons drew its own letter tile because the
            // site has no icon. We already have a letter, in our own palette.
            if (icon.source == Icon.Source.GENERATOR) return@launch
            if (boundUrl != url) return@launch // row was recycled mid-fetch
            showFavicon(icon.bitmap)
        }
    }

    /**
     * Show a favicon we already hold — tab state carries one, so a tab row has
     * no reason to go and ask for it again.
     */
    fun bindSite(url: String, bitmap: Bitmap?, icons: BrowserIcons, scope: CoroutineScope) {
        if (bitmap == null) {
            bindSite(url, icons, scope)
            return
        }
        showLetter(HostTile.hostOf(url))
        boundUrl = url
        showFavicon(bitmap)
    }

    /**
     * Show a glyph instead of a site — a magnifier for a search suggestion, a
     * folder for a download. Neutral colours: this isn't a site and shouldn't
     * borrow a site's identity colour.
     */
    fun bindGlyph(@DrawableRes res: Int) {
        job?.cancel()
        boundUrl = null
        binding.siteLetter.isVisible = false
        binding.siteIcon.isVisible = true
        binding.siteIcon.background = null
        binding.siteIcon.setPadding(glyphPadding, glyphPadding, glyphPadding, glyphPadding)
        binding.siteIcon.setImageResource(res)
        binding.siteIcon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
        )
        backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainerHighest,
            ),
        )
    }

    private fun showLetter(host: String) {
        job?.cancel()
        binding.siteLetter.text = HostTile.letterFor(host)
        binding.siteLetter.isVisible = true
        binding.siteIcon.isVisible = false
        binding.siteIcon.setImageDrawable(null)
        backgroundTintList = ColorStateList.valueOf(HostTile.colorFor(host))
    }

    private fun showFavicon(bitmap: Bitmap) = with(binding.siteIcon) {
        setPadding(faviconPadding, faviconPadding, faviconPadding, faviconPadding)
        background = ContextCompat.getDrawable(context, R.drawable.bg_site_icon_plate)
        imageTintList = null
        setImageBitmap(bitmap)
        isVisible = true
    }

    private companion object {
        /** Enough white plate around the icon that it reads as a favicon. */
        const val FAVICON_PADDING_DP = 7

        /** Glyphs are line art and want more air than a filled favicon does. */
        const val GLYPH_PADDING_DP = 9
    }
}
