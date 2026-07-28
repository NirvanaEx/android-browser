package com.upgrid.browser.menu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.upgrid.browser.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.HitResult
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * The long-press menu on a link, an image or a media element.
 *
 * Gecko reports a long press as a [HitResult] parked on the tab; this watches
 * for one, offers what makes sense for it, and consumes it either way — an
 * unconsumed hit result means the next long press on the same element is
 * ignored, because the store's value never changed.
 *
 * Written here rather than taken from `feature-contextmenu`, which is the same
 * decision this project has already made for downloads, find-in-page and the
 * omnibar drop-down, and for a sharper reason than usual: that artifact depends
 * on `feature-search`, which drags in `support-remotesettings`, appservices and
 * Glean — and Glean's native library is *already inside* `geckoview-omni`, so
 * Gradle refuses the build outright with a capability conflict on
 * `org.mozilla.telemetry:glean-native`. Resolving that conflict is three lines
 * of `capabilitiesResolution`; the megabytes of telemetry machinery it would
 * then ship, in a browser whose whole pitch is that it doesn't phone home, are
 * not worth a menu with six rows in it.
 *
 * Downloading is a dispatch rather than a call: `UpdateDownloadAction` is what
 * [com.upgrid.browser.download.DownloadManager] already watches for, and it
 * re-fetches with the page as referrer, which is what makes an image behind a
 * hotlink check actually arrive.
 */
class LinkContextMenu(
    private val activity: Activity,
    private val store: BrowserStore,
    private val tabsUseCases: TabsUseCases,
    private val haptics: () -> Boolean = { true },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : LifecycleAwareFeature {

    private var scope: CoroutineScope? = null
    private var dialog: AlertDialog? = null

    /** One row: what it says, and what it does. */
    private class Row(@param:StringRes val label: Int, val act: () -> Unit)

    override fun start() {
        scope = store.flowScoped(dispatcher = mainDispatcher) { flow ->
            flow.map { it.selectedTab }
                .distinctUntilChangedBy { it?.content?.hitResult }
                .collect { tab ->
                    val hit = tab?.content?.hitResult ?: return@collect
                    show(tab, hit)
                    store.dispatch(ContentAction.ConsumeHitResultAction(tab.id))
                }
        }
    }

    override fun stop() {
        scope?.cancel()
        // The dialog holds the activity's window; a rotation with it open
        // would leak one otherwise.
        dialog?.dismiss()
        dialog = null
    }

    private fun show(tab: TabSessionState, hit: HitResult) {
        val rows = rowsFor(tab, hit)
        if (rows.isEmpty()) return

        // A second long press while one is open: the first one wins rather than
        // stacking a dialog behind a dialog.
        if (dialog?.isShowing == true) return

        buzz()

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title(hit))
            .setItems(rows.map(::labelOf).toTypedArray()) { _, which -> rows[which].act() }
            .setOnDismissListener { dialog = null }
            .show()
    }

    /**
     * The tick that says the long press worked.
     *
     * This is the one gesture in a browser with no other feedback: the finger
     * is already down, nothing on screen moves, and the only way to learn
     * whether you have held it long enough is to wait and find out. A short
     * buzz at the moment the menu is decided answers that before the dialog has
     * even drawn.
     *
     * `LONG_PRESS` rather than `KEYBOARD_TAP` because Android reserves it for
     * exactly this, and because a phone that already buzzes for the launcher's
     * long press should buzz the same way here. [View.performHapticFeedback] is
     * a no-op when the user has turned haptics off system-wide, so the phone's
     * own setting is honoured without asking about it.
     */
    private fun buzz() {
        if (!haptics()) return
        runCatching {
            activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun labelOf(row: Row): CharSequence = activity.getString(row.label)

    /**
     * What the menu is about, in one line: the address, without its scheme, cut
     * to something that fits a title. A base64 `data:` image is a legitimate
     * hit result and is thousands of characters long.
     */
    private fun title(hit: HitResult): String {
        val target = link(hit).ifBlank { hit.src }
        val trimmed = target.removePrefix("https://").removePrefix("http://")
        return if (trimmed.length <= MAX_TITLE) trimmed else trimmed.take(MAX_TITLE) + "…"
    }

    /**
     * The address a long press is *about*.
     *
     * [HitResult.IMAGE_SRC] is the interesting one: an image wrapped in a link,
     * where `src` is the picture and `uri` is where the link goes. Both are
     * worth offering, and confusing them is how "open in new tab" ends up
     * loading a JPEG.
     */
    private fun link(hit: HitResult): String = when (hit) {
        is HitResult.IMAGE_SRC -> hit.uri
        is HitResult.UNKNOWN -> hit.src
        else -> ""
    }

    private fun image(hit: HitResult): String = when (hit) {
        is HitResult.IMAGE -> hit.src
        is HitResult.IMAGE_SRC -> hit.src
        else -> ""
    }

    private fun media(hit: HitResult): String = when (hit) {
        is HitResult.VIDEO, is HitResult.AUDIO -> hit.src
        else -> ""
    }

    private fun rowsFor(tab: TabSessionState, hit: HitResult): List<Row> {
        val rows = mutableListOf<Row>()

        val link = link(hit)
        if (link.isWeb()) {
            rows += Row(R.string.context_open_new_tab) {
                tabsUseCases.addTab(url = link, selectTab = false, parentId = tab.id)
                toast(R.string.context_opened_in_background)
            }
            rows += Row(R.string.context_copy_link) { copy(link) }
            rows += Row(R.string.context_share_link) { share(link) }
            // Only for something that isn't a page. "Download" on an article is
            // an offer to save its HTML, which nobody wants and which the
            // download list would then show as a file called index.
            if (!link.looksLikePage()) {
                rows += Row(R.string.context_download_link) { download(tab, link) }
            }
        }

        val image = image(hit)
        if (image.isWeb()) {
            rows += Row(R.string.context_open_image) {
                tabsUseCases.addTab(url = image, selectTab = false, parentId = tab.id)
                toast(R.string.context_opened_in_background)
            }
            rows += Row(R.string.context_save_image) { download(tab, image) }
            rows += Row(R.string.context_copy_image_link) { copy(image) }
        }

        val media = media(hit)
        if (media.isWeb()) {
            rows += Row(R.string.context_save_media) { download(tab, media) }
            rows += Row(R.string.context_copy_link) { copy(media) }
        }

        when (hit) {
            is HitResult.EMAIL -> {
                val address = hit.src.removePrefix("mailto:")
                rows += Row(R.string.context_write_email) { view(hit.src) }
                rows += Row(R.string.context_copy_email) { copy(address) }
            }
            is HitResult.PHONE -> {
                val number = hit.src.removePrefix("tel:")
                rows += Row(R.string.context_call) { view(hit.src) }
                rows += Row(R.string.context_copy_phone) { copy(number) }
            }
            else -> Unit
        }

        return rows
    }

    // --- Actions -----------------------------------------------------------

    private fun copy(text: String) {
        activity.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.app_name), text))
        toast(R.string.context_copied)
    }

    private fun share(url: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        runCatching {
            activity.startActivity(
                Intent.createChooser(intent, activity.getString(R.string.context_share_link)),
            )
        }
    }

    /** Open a `mailto:` or `tel:` in whatever handles it. */
    private fun view(uri: String) {
        val opened = runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }.isSuccess
        if (!opened) toast(R.string.context_no_app)
    }

    private fun download(tab: TabSessionState, url: String) {
        store.dispatch(
            ContentAction.UpdateDownloadAction(
                tab.id,
                DownloadState(
                    url = url,
                    skipConfirmation = true,
                    referrerUrl = tab.content.url,
                    private = tab.content.private,
                ),
            ),
        )
    }

    private fun toast(@StringRes message: Int) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private fun String.isWeb() = startsWith("http://") || startsWith("https://")

    /** A page, as opposed to a file worth offering to save. */
    private fun String.looksLikePage(): Boolean {
        val path = substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return true // no extension at all
        return last.endsWith(".html", true) ||
            last.endsWith(".htm", true) ||
            last.endsWith(".php", true) ||
            last.endsWith(".aspx", true)
    }

    private companion object {
        /** Two lines of dialog title at a readable size. */
        const val MAX_TITLE = 80
    }
}
