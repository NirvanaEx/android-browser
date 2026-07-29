package com.upgrid.browser.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One site, as seen across history: how many of its pages, and when last. */
data class HostEntry(
    val host: String,
    val pages: Int,
    val lastVisit: Long,
)

/** One visited page. [visits] counts how many times the URL has been opened. */
data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String,
    val host: String,
    val visitedAt: Long,
    val visits: Int,
)

/**
 * Browsing history, backed by a small SQLite table.
 *
 * Deliberately NOT `browser-storage-sync` (the Places-backed store the roadmap
 * in CLAUDE.md sketched for phase 3). That pulls the application-services
 * megazord — tens of MB of native code per ABI, on top of GeckoView's — and
 * ties history to an a-c version we already pin by hand. We use none of what
 * it buys (Sync accounts, frecency ranking, bookmark trees), so this is a
 * plain table instead. Revisit if/when Firefox Sync is on the roadmap.
 *
 * Shape: one row per URL, not one per visit. Re-visiting bumps `visited_at`
 * and increments `visits` rather than appending, which is what a history LIST
 * wants — otherwise refreshing a page ten times buries everything else. The
 * unique index on `url` makes that an upsert.
 *
 * Every public method suspends onto [dbContext]; none of this may run on the
 * main thread — [record] is called from the store observer, which ticks dozens
 * of times per page load.
 */
class HistoryStore(context: Context) {

    private val helper = Helper(context.applicationContext)

    /**
     * All DB work runs here, and it is deliberately single-threaded.
     *
     * [record] and [updateTitle] are launched as separate coroutines from the
     * store observer within milliseconds of each other. On the shared IO pool
     * they race, and `updateTitle` — much the cheaper statement — can win and
     * update a row `record` hasn't inserted yet, silently dropping the title.
     * Serialising makes the pair ordered by construction.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dbContext = Dispatchers.IO.limitedParallelism(1)

    /**
     * Record a visit. Silently ignores anything that isn't a real page:
     * the synthetic home URL, about:/data:/blob: schemes, and blanks.
     *
     * [title] is often empty at first — the page fires its URL change before
     * the title resolves. We never overwrite a good stored title with an empty
     * one, so the later call with the real title wins.
     */
    suspend fun record(url: String, title: String) = withContext(dbContext) {
        if (!isRecordable(url)) return@withContext
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // COALESCE(NULLIF(?,''), title) keeps the existing title when the
            // incoming one is blank; the excluded-row trick isn't available on
            // the SQLite shipped with API 26, so this is a plain UPSERT body.
            val updated = db.compileStatement(
                """
                UPDATE $TABLE
                   SET visited_at = ?,
                       visits = visits + 1,
                       title = COALESCE(NULLIF(?, ''), title)
                 WHERE url = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.bindLong(1, now)
                stmt.bindString(2, title)
                stmt.bindString(3, url)
                stmt.executeUpdateDelete()
            }

            if (updated == 0) {
                db.insertWithOnConflict(
                    TABLE, null,
                    ContentValues().apply {
                        put("url", url)
                        put("title", title)
                        put("host", hostOf(url))
                        put("visited_at", now)
                        put("visits", 1)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                trim(db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Attach a title to an already-recorded URL without counting a new visit.
     *
     * Pages hand us their `<title>` after the load settles, and SPAs rewrite it
     * mid-session; routing those through [record] would inflate every row's
     * visit counter. No-op when the URL isn't in the table yet.
     */
    suspend fun updateTitle(url: String, title: String) = withContext(dbContext) {
        if (title.isBlank() || !isRecordable(url)) return@withContext
        helper.writableDatabase.update(
            TABLE,
            ContentValues().apply { put("title", title) },
            "url = ?",
            arrayOf(url),
        )
        Unit
    }

    /** Most-recent first. [query] filters on URL or title when non-blank. */
    suspend fun entries(query: String = "", limit: Int = PAGE_SIZE): List<HistoryEntry> =
        withContext(dbContext) {
            val trimmed = query.trim()
            val (where, args) = if (trimmed.isEmpty()) {
                null to emptyArray<String>()
            } else {
                val like = "%$trimmed%"
                "url LIKE ? OR title LIKE ?" to arrayOf(like, like)
            }
            helper.readableDatabase.query(
                TABLE, null, where, args, null, null, "visited_at DESC", limit.toString()
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            HistoryEntry(
                                id = c.getLong(c.getColumnIndexOrThrow("_id")),
                                url = c.getString(c.getColumnIndexOrThrow("url")),
                                title = c.getString(c.getColumnIndexOrThrow("title")).orEmpty(),
                                host = c.getString(c.getColumnIndexOrThrow("host")).orEmpty(),
                                visitedAt = c.getLong(c.getColumnIndexOrThrow("visited_at")),
                                visits = c.getInt(c.getColumnIndexOrThrow("visits")),
                            )
                        )
                    }
                }
            }
        }

    /**
     * Fold a remote history set into the local table — the pull half of Drive
     * sync. Union by URL, never subtraction; see
     * [com.upgrid.browser.bookmarks.BookmarkStore.mergeIn] for why.
     *
     * `visits` takes the MAX of the two counts rather than their sum. Summing
     * looks more correct for a moment and is badly wrong over time: every sync
     * re-reads the same remote number and adds it again, so a page visited
     * twice climbs into the hundreds after a week of syncing. MAX converges.
     */
    suspend fun mergeIn(incoming: List<HistoryEntry>) = withContext(dbContext) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            incoming.forEach { entry ->
                if (!isRecordable(entry.url)) return@forEach
                val updated = db.compileStatement(
                    """
                    UPDATE $TABLE
                       SET visited_at = MAX(visited_at, ?),
                           visits = MAX(visits, ?),
                           title = COALESCE(NULLIF(?, ''), title)
                     WHERE url = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.bindLong(1, entry.visitedAt)
                    stmt.bindLong(2, entry.visits.toLong())
                    stmt.bindString(3, entry.title)
                    stmt.bindString(4, entry.url)
                    stmt.executeUpdateDelete()
                }
                if (updated == 0) {
                    db.insertWithOnConflict(
                        TABLE, null,
                        ContentValues().apply {
                            put("url", entry.url)
                            put("title", entry.title)
                            put("host", hostOf(entry.url))
                            put("visited_at", entry.visitedAt)
                            put("visits", entry.visits)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
            trim(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun delete(id: Long) = withContext(dbContext) {
        helper.writableDatabase.delete(TABLE, "_id = ?", arrayOf(id.toString()))
        Unit
    }

    /**
     * Drop one page, by address rather than by row id.
     *
     * The omnibar's long-press: a [Suggestion] carries what it needs to load,
     * not where it came from — and there is one row per URL in this table, so
     * the address identifies it exactly.
     */
    suspend fun forget(url: String) = withContext(dbContext) {
        helper.writableDatabase.delete(TABLE, "url = ?", arrayOf(url))
        Unit
    }

    suspend fun clearAll() = withContext(dbContext) {
        helper.writableDatabase.delete(TABLE, null, null)
        Unit
    }

    /** Drop everything visited at or after [since]. Used by "clear last week". */
    suspend fun deleteSince(since: Long) = withContext(dbContext) {
        helper.writableDatabase.delete(TABLE, "visited_at >= ?", arrayOf(since.toString()))
        Unit
    }

    /**
     * Distinct hosts, most-recently-visited first, with how many pages of each
     * are on record.
     *
     * This is the closest thing to a list of "sites that have data on this
     * device": the engine can delete a site's cookies and storage by host but
     * has no API to enumerate which hosts have any. Every site the browser has
     * data for is a site it visited, so history is the index.
     *
     * [since] of 0 means all of it.
     */
    suspend fun hosts(since: Long = 0L, limit: Int = HOST_LIMIT): List<HostEntry> =
        withContext(dbContext) {
            helper.readableDatabase.rawQuery(
                """
                SELECT host, COUNT(*) AS pages, MAX(visited_at) AS last
                  FROM $TABLE
                 WHERE host != '' AND visited_at >= ?
                 GROUP BY host
                 ORDER BY last DESC
                 LIMIT ?
                """.trimIndent(),
                arrayOf(since.toString(), limit.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            HostEntry(
                                host = c.getString(0).orEmpty(),
                                pages = c.getInt(1),
                                lastVisit = c.getLong(2),
                            ),
                        )
                    }
                }
            }
        }

    /** Forget every page from one site. Pairs with clearing its site data. */
    suspend fun deleteHost(host: String) = withContext(dbContext) {
        helper.writableDatabase.delete(TABLE, "host = ?", arrayOf(host))
        Unit
    }

    suspend fun count(): Int = withContext(dbContext) {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // --- Internals ---------------------------------------------------------

    /** Drop the oldest rows once the table outgrows [MAX_ROWS]. */
    private fun trim(db: SQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM $TABLE WHERE _id NOT IN (
                SELECT _id FROM $TABLE ORDER BY visited_at DESC LIMIT $MAX_ROWS
            )
            """.trimIndent()
        )
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    _id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    url        TEXT NOT NULL,
                    title      TEXT NOT NULL DEFAULT '',
                    host       TEXT NOT NULL DEFAULT '',
                    visited_at INTEGER NOT NULL,
                    visits     INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX idx_${TABLE}_url ON $TABLE(url)")
            db.execSQL("CREATE INDEX idx_${TABLE}_time ON $TABLE(visited_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 is the first shipped schema; nothing to migrate from yet.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "upgrid_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "visits"

        /** Roughly a year of heavy browsing; the trim keeps writes bounded. */
        private const val MAX_ROWS = 5000
        const val PAGE_SIZE = 500

        /** More sites than anyone will scroll; keeps the grouping query bounded. */
        private const val HOST_LIMIT = 500

        /** Schemes that are chrome, not content — never worth remembering. */
        private val SKIPPED_SCHEMES = setOf("about", "data", "blob", "javascript", "resource")

        fun isRecordable(url: String): Boolean {
            if (url.isBlank()) return false
            val scheme = Uri.parse(url).scheme?.lowercase() ?: return false
            return scheme !in SKIPPED_SCHEMES
        }

        fun hostOf(url: String): String =
            runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    }
}
