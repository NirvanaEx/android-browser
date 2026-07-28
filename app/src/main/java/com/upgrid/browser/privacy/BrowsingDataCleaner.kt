package com.upgrid.browser.privacy

import android.content.Context
import androidx.annotation.StringRes
import com.upgrid.browser.BrowserComponents
import com.upgrid.browser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.concept.engine.Engine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Deleting what the browser has kept.
 *
 * The period is the awkward part and it is worth being explicit about, because
 * the UI promises something the engine cannot quite do. Our own tables —
 * history, past searches — have timestamps, so "the last week" is exact. Gecko's
 * cookie jar and caches do not expose one: `StorageController` clears
 * everything, or everything belonging to one host, and nothing in between.
 *
 * So a bounded period clears site data **for the sites visited in that period**,
 * which is the same answer for anyone whose question was "forget where I've
 * been since Monday", and the only honest approximation available. "All time"
 * takes the direct route and wipes the lot. The wording in the dialog says so.
 */
class BrowsingDataCleaner(
    private val context: Context,
    private val components: BrowserComponents,
) {

    /** How far back to go. */
    enum class Period(@StringRes val label: Int, val millis: Long) {
        DAY(R.string.clear_period_day, TimeUnit.DAYS.toMillis(1)),
        WEEK(R.string.clear_period_week, TimeUnit.DAYS.toMillis(7)),
        MONTH(R.string.clear_period_month, TimeUnit.DAYS.toMillis(30)),
        ALL(R.string.clear_period_all, 0L);

        /** Epoch millis to delete from, or 0 for everything. */
        fun since(now: Long = System.currentTimeMillis()): Long =
            if (this == ALL) 0L else now - millis
    }

    /** What to delete. Each is a checkbox in the dialog. */
    enum class DataType(@StringRes val label: Int) {
        HISTORY(R.string.clear_type_history),
        SEARCHES(R.string.clear_type_searches),
        COOKIES(R.string.clear_type_cookies),
        CACHE(R.string.clear_type_cache),
        PASSWORDS(R.string.clear_type_passwords),
    }

    /**
     * Delete [types] over [period]. Returns when the engine has answered, or
     * after [ENGINE_TIMEOUT_MS] — a callback that never fires must not leave a
     * progress dialog up forever.
     */
    suspend fun clear(period: Period, types: Set<DataType>) {
        val since = period.since()

        if (DataType.SEARCHES in types) components.searchHistory.clear()
        if (DataType.PASSWORDS in types) components.logins.clear()

        // Site data first, because it is derived from history: clearing the
        // history and then asking which sites to clean would find nothing.
        val engineData = engineData(types)
        if (engineData != null) {
            if (period == Period.ALL) {
                clearEngineData(engineData, host = null)
            } else {
                components.browsingHistory.hosts(since).forEach { entry ->
                    clearEngineData(engineData, host = entry.host)
                }
            }
        }

        if (DataType.HISTORY in types) {
            if (period == Period.ALL) {
                components.browsingHistory.clearAll()
            } else {
                components.browsingHistory.deleteSince(since)
            }
        }
    }

    /** Everything one site left behind: cookies, storage, caches, permissions. */
    suspend fun clearSite(host: String) {
        clearEngineData(Engine.BrowsingData.allSiteData(), host)
        components.browsingHistory.deleteHost(host)
    }

    private fun engineData(types: Set<DataType>): Engine.BrowsingData? {
        val cookies = DataType.COOKIES in types
        val cache = DataType.CACHE in types
        return when {
            cookies && cache -> Engine.BrowsingData.allSiteData()
            cookies -> Engine.BrowsingData.select(
                Engine.BrowsingData.COOKIES,
                Engine.BrowsingData.DOM_STORAGES,
                Engine.BrowsingData.AUTH_SESSIONS,
            )
            cache -> Engine.BrowsingData.allCaches()
            else -> null
        }
    }

    /**
     * `Engine.clearData` is callback-shaped and must be called on the main
     * thread — it goes through to GeckoView's StorageController, which is main
     * thread only.
     */
    private suspend fun clearEngineData(data: Engine.BrowsingData, host: String?) {
        withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    components.engine.clearData(
                        data = data,
                        host = host,
                        onSuccess = { if (continuation.isActive) continuation.resume(Unit) },
                        onError = { if (continuation.isActive) continuation.resume(Unit) },
                    )
                }
            }
        }
    }

    /**
     * Roughly how much disk the browser is sitting on.
     *
     * Roughly on purpose: the engine's profile holds the network cache, the
     * image cache and the site storage in one tree and offers no accounting, so
     * this walks the directories the app owns and adds them up. It is the right
     * order of magnitude, which is what the number is for — the exact figure
     * would be a different feature.
     */
    suspend fun approximateCacheBytes(): Long = withContext(Dispatchers.IO) {
        val roots = listOfNotNull(
            context.cacheDir,
            context.externalCacheDir,
            File(context.filesDir, "mozilla"),
        )
        var budget = MAX_FILES_WALKED
        var total = 0L
        for (root in roots) {
            if (!root.exists()) continue
            for (file in root.walkTopDown()) {
                // `break`, not a skip: the budget is there to bound the walk,
                // and stepping over files one at a time wouldn't.
                if (budget-- <= 0) break
                if (file.isFile) total += file.length()
            }
        }
        total
    }

    private companion object {
        const val ENGINE_TIMEOUT_MS = 15_000L

        /** A profile with more files than this is one nobody is waiting on. */
        const val MAX_FILES_WALKED = 40_000
    }
}
