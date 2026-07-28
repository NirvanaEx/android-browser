package com.upgrid.browser.tabs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import com.upgrid.browser.MainActivity
import kotlinx.coroutines.flow.mapNotNull
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.window.WindowRequest
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.ktx.kotlinx.coroutines.flow.filterChanged

/**
 * Links that want a window of their own.
 *
 * `target="_blank"`, `window.open()` and `window.close()` all arrive here.
 * Gecko has already built a session for the new window by the time we hear
 * about it — `onNewSession` hands one back immediately — and parks a
 * [WindowRequest] on the tab. Something has to pick that up and turn it into a
 * tab; nothing did, which is why those links did nothing at all. Not "opened in
 * the wrong place", not "opened and closed": no reaction whatsoever, on a link
 * that looks exactly like every other link. On sites that route their whole
 * navigation through `window.open` — and on any page whose outbound links carry
 * `target="_blank"`, which is most news and aggregator pages — that reads as a
 * page where the links are decoration.
 *
 * a-c ships [mozilla.components.feature.tabs.WindowFeature] for this, and this
 * class is that feature plus one decision: whether a new window becomes a new
 * tab at all. With [openInNewTab] off the page is loaded where the user already
 * is, which is what people who dislike tab-spawning actually want — and the
 * session Gecko prepared is closed rather than left running invisibly.
 *
 * The preference is read per request, not captured at construction: it lives in
 * Settings and can be changed while the browser is open.
 */
class WindowRequests(
    private val store: BrowserStore,
    private val tabsUseCases: TabsUseCases,
    private val sessionUseCases: SessionUseCases,
    private val openInNewTab: () -> Boolean,
    /** Everything here touches tabs and engine sessions, so: the main thread. */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : LifecycleAwareFeature {

    private var scope: CoroutineScope? = null

    override fun start() {
        scope = store.flowScoped(dispatcher = mainDispatcher) { flow ->
            flow.mapNotNull { state -> state.tabs }
                .filterChanged { it.content.windowRequest }
                .collect { tab ->
                    when (tab.content.windowRequest?.type) {
                        WindowRequest.Type.OPEN -> consume(tab) { open(tab, it) }
                        WindowRequest.Type.CLOSE -> consume(tab) { close(it) }
                        // The request was consumed rather than added; that is
                        // this flow reporting our own work back to us.
                        null -> Unit
                    }
                }
        }
    }

    override fun stop() {
        scope?.cancel()
    }

    private fun consume(tab: SessionState, action: (WindowRequest) -> Unit) {
        tab.content.windowRequest?.let(action)
        store.dispatch(ContentAction.ConsumeWindowRequestAction(tab.id))
    }

    private fun open(parent: SessionState, request: WindowRequest) {
        val session = request.prepare()

        // A blank URL means the page opened a window and will write into it
        // from script — there is nothing to load anywhere else, so that one
        // always gets its own tab regardless of the preference.
        if (openInNewTab() || request.url.isBlank()) {
            tabsUseCases.addTab(
                // The URL matters even though the engine is already loading it.
                // Without it the tab is created on about:blank, which is this
                // browser's "home", so selecting it flashed the speed dial for
                // as long as the first bytes took to arrive — a link that
                // visibly went to the start page and then somewhere else.
                // Passing it costs nothing: addTab only issues a load of its
                // own when there is no engine session, and here there is one.
                url = request.url.ifBlank { MainActivity.HOME_URL },
                selectTab = true,
                parentId = parent.id,
                engineSession = session,
                private = parent.content.private,
            )
            request.start()
            return
        }

        // Staying put: the session Gecko built for the window is never going to
        // be rendered, and an open GeckoSession costs a content process.
        runCatching { session.close() }
        sessionUseCases.loadUrl(request.url, parent.id)
    }

    private fun close(request: WindowRequest) {
        val session = request.prepare()
        store.state.tabs.find { it.engineState.engineSession === session }
            ?.let { tabsUseCases.removeTab(it.id) }
    }
}
