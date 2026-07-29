package com.upgrid.browser.ui

import android.net.Uri
import mozilla.components.browser.icons.IconRequest

/**
 * Where a site's real logo comes from.
 *
 * `BrowserIcons` does **not** go looking for one by itself. Given a bare
 * [IconRequest] it consults, in order: its bundled "tippy top" list (a few
 * hundred well-known sites), the memory cache, the disk cache — and if none of
 * those has an answer it draws a letter tile and calls that the icon. There is
 * no step where it asks the site. So every host that isn't famous and hasn't
 * been visited *in this install* got a coloured letter, which is what "logos
 * are placeholders" meant.
 *
 * The fix is to name the two places a browser has always looked, as explicit
 * candidates on the request:
 *
 *  - `/apple-touch-icon.png` — usually 180×180, so it survives being drawn at
 *    the 56dp of a speed-dial tile. Ranked above favicons by a-c's own
 *    comparator, so it is tried first when it exists.
 *  - `/favicon.ico` — the twenty-five-year-old convention, still the most
 *    widely present file on the web.
 *
 * Both are requests to **the site itself** — not to an icon service. Passing
 * every host somebody visits to a third party for a picture is exactly the kind
 * of quiet phoning-home this browser exists to not do, and it would arrive at
 * whoever runs the service as a browsing history.
 *
 * Cheap to be wrong: a site with neither file answers 404 twice, `HttpIconLoader`
 * remembers the failure and stops asking, and the letter tile stays. A site with
 * either gets its real logo cached to disk on first sight and costs nothing
 * afterwards.
 */
object Favicons {

    /**
     * An icon request for [url] that can actually find a logo.
     *
     * Anything the engine already discovered from the page (a `<link rel=icon>`,
     * a web manifest) is added on top of these by the disk preparer and outranks
     * them, so this only fills the gap where nothing is known.
     */
    fun request(url: String): IconRequest {
        val host = runCatching { Uri.parse(url) }.getOrNull()
            ?.takeIf { it.scheme == "http" || it.scheme == "https" }
            ?.host
            .orEmpty()
        if (host.isBlank()) return IconRequest(url = url)

        return IconRequest(
            url = url,
            resources = listOf(
                IconRequest.Resource(
                    url = "https://$host/apple-touch-icon.png",
                    type = IconRequest.Resource.Type.APPLE_TOUCH_ICON,
                ),
                IconRequest.Resource(
                    url = "https://$host/favicon.ico",
                    type = IconRequest.Resource.Type.FAVICON,
                ),
            ),
        )
    }
}
