package org.mozilla.fenix.upgrid

import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import java.util.WeakHashMap

/** Prevent home routing while an existing home tab waits for Gecko's new location. */
object UpgridHomeNavigation {
    // Only a tab ID is retained; no page URL, input text or private session data.
    private val leavingHome = WeakHashMap<BrowserStore, String>()

    @Synchronized
    fun onLoadStarted(store: BrowserStore, input: String) {
        val tab = store.state.selectedTab
        if (tab?.content?.url == ABOUT_HOME_URL && input != ABOUT_HOME_URL) {
            leavingHome[store] = tab.id
        } else {
            leavingHome.remove(store)
        }
    }

    @Synchronized
    fun shouldShowHome(store: BrowserStore): Boolean {
        val tab = store.state.selectedTab
        val pending = leavingHome[store]
        if (pending != null && (tab == null || tab.id != pending || tab.content.url != ABOUT_HOME_URL)) {
            leavingHome.remove(store)
        }
        return tab != null && tab.content.url == ABOUT_HOME_URL && leavingHome[store] != tab.id
    }

    @Synchronized
    fun clear(store: BrowserStore) {
        leavingHome.remove(store)
    }
}
