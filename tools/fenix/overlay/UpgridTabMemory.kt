package org.mozilla.fenix.upgrid

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import kotlin.coroutines.resume

/** Keep recent tabs warm, and release only idle, restorable ordinary pages. */
class UpgridTabMemory(
    private val store: BrowserStore,
    private val scope: CoroutineScope = MainScope(),
    private val now: () -> Long = System::currentTimeMillis,
) : DefaultLifecycleObserver {
    private var job: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        if (job != null) return
        job = scope.launch {
            delay(CHECK_INTERVAL_MS)
            while (true) {
                val suspended = trimOne()
                // Never close several decoders/processes in one UI turn.
                delay(if (suspended) 1_000L else CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
    }

    internal suspend fun trimOne(): Boolean {
        val candidate = candidates(store.state, now()).firstOrNull() ?: return false
        val engine = candidate.engineState.engineSession ?: return false
        // A missing/failed/stale form-data check must never count as an empty form.
        if (containsFormData(engine) != false) return false
        return synchronized(store) {
            // Store.dispatch is synchronous under this same monitor in the pinned
            // A-C version. Selection/form/media changes cannot race this check and unlink.
            val tab = candidates(store.state, now()).find { it.id == candidate.id } ?: return@synchronized false
            if (tab.engineState.engineSession !== engine || tab.content.url != candidate.content.url ||
                tab.lastAccess != candidate.lastAccess || tab.lastVisibleAt != candidate.lastVisibleAt) return@synchronized false
            store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
            Log.i("UpgridTabMemory", "idle_session_suspended")
            true
        }
    }

    private suspend fun containsFormData(engine: EngineSession): Boolean? = withTimeoutOrNull(2_000L) {
        suspendCancellableCoroutine<Boolean?> { continuation ->
            val observer = object : EngineSession.Observer {
                override fun onCheckForFormData(containsFormData: Boolean, adjustPriority: Boolean) {
                    engine.unregister(this)
                    if (continuation.isActive) continuation.resume(containsFormData)
                }
            }
            engine.register(observer)
            continuation.invokeOnCancellation { engine.unregister(observer) }
            try { engine.checkForFormData(adjustPriority = false) }
            catch (_: Exception) {
                engine.unregister(observer)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    companion object {
        internal const val KEEP_RECENT = 5
        // A minute away is ordinary tab switching, not an idle session. Keep
        // page scripts and unsaved in-memory state alive through normal browsing.
        // This is an engine unload delay, never a tab/data expiration deadline.
        internal const val IDLE_MS = 30 * 60_000L
        private const val CHECK_INTERVAL_MS = 15_000L

        internal fun candidates(state: BrowserState, now: Long): List<TabSessionState> {
            val loaded = state.tabs.filter { it.engineState.engineSession != null }
            if (loaded.size <= KEEP_RECENT) return emptyList()
            val recent = loaded.sortedWith(
                compareByDescending<TabSessionState> { it.id == state.selectedTabId }
                    .thenByDescending { maxOf(it.lastAccess, it.lastVisibleAt, it.createdAt) },
            ).take(KEEP_RECENT).mapTo(mutableSetOf()) { it.id }
            val downloads = state.downloads.values.filter {
                it.status in setOf(DownloadState.Status.INITIATED, DownloadState.Status.DOWNLOADING, DownloadState.Status.PAUSED)
            }.mapTo(mutableSetOf()) { it.sessionId }
            return loaded.filter { tab ->
                val page = tab.content
                tab.id !in recent && tab.id != state.selectedTabId &&
                    now - maxOf(tab.lastAccess, tab.lastVisibleAt, tab.createdAt) >= IDLE_MS &&
                    !page.private && (page.url.startsWith("https://") || page.url.startsWith("http://")) &&
                    tab.engineState.engineSessionState != null && !tab.engineState.initializing &&
                    !tab.engineState.crashed && !page.loading && !page.hasFormData &&
                    !page.fullScreen && !page.pictureInPictureEnabled && !page.isPdf &&
                    page.promptRequests.isEmpty() && page.permissionRequestsList.isEmpty() &&
                    page.appPermissionRequestsList.isEmpty() && page.recordingDevices.isEmpty() &&
                    tab.mediaSessionState == null && !tab.lastMediaAccessState.mediaSessionActive &&
                    tab.lastMediaAccessState.lastMediaUrl != page.url && tab.id !in downloads
            }.sortedBy { maxOf(it.lastAccess, it.lastVisibleAt, it.createdAt) }
        }
    }
}
