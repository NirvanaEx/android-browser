package org.mozilla.fenix.upgrid

import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import java.util.WeakHashMap

/** Prevent home routing while an existing home tab waits for Gecko's new location. */
object UpgridHomeNavigation {
    // Keep departures per tab until Gecko acknowledges them, including across
    // tab switches and Activity stop/start. Retain IDs only, never input or URLs.
    private val leavingHome = WeakHashMap<BrowserStore, MutableSet<String>>()

    @Synchronized
    fun onLoadStarted(store: BrowserStore, input: String) {
        val tab = store.state.selectedTab
        if (tab == null) return
        prune(store)
        if (tab.content.url == ABOUT_HOME_URL && input != ABOUT_HOME_URL) {
            leavingHome.getOrPut(store) { mutableSetOf() }.add(tab.id)
        } else {
            leavingHome[store]?.remove(tab.id)
        }
    }

    @Synchronized
    fun shouldShowHome(store: BrowserStore): Boolean {
        val tab = store.state.selectedTab
        prune(store)
        return tab != null && tab.content.url == ABOUT_HOME_URL && leavingHome[store]?.contains(tab.id) != true
    }

    private fun prune(store: BrowserStore) {
        val homeIds = store.state.tabs.filter { it.content.url == ABOUT_HOME_URL }.map { it.id }.toSet()
        leavingHome[store]?.let { pending ->
            pending.retainAll(homeIds)
            if (pending.isEmpty()) leavingHome.remove(store)
        }
    }
}
