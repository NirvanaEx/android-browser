package com.upgrid.browser.bookmarks

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.upgrid.browser.ui.HostTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One saved page. [createdAt] is when the star was first tapped. */
data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val host: String,
    val createdAt: Long,
)

/**
 * Bookmarks, backed by a small SQLite table.
 *
 * Same reasoning as [com.upgrid.browser.history.HistoryStore]: a flat table
 * rather than `browser-storage-sync`'s Places-backed bookmark tree, which would
 * pull the application-services megazord for a feature that is, at this size, a
 * list of URLs. There are deliberately **no folders** — a folder tree needs a
 * tree UI, a move gesture and a breadcrumb, and none of that fits the
 * "minimal chrome, big tap targets" direction the app is built around.
 *
 * One row per URL. Starring a page that is already saved is a no-op rather than
 * a duplicate, which is what makes [toggle] safe to wire straight to a button.
 *
 * Every method suspends onto a single-threaded IO context, for the same reason
 * the history store does: the toggle and the subsequent list refresh are
 * launched as separate coroutines microseconds apart and must not race.
 */
class BookmarkStore(context: Context) {

    private val helper = Helper(context.applicationContext)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dbContext = Dispatchers.IO.limitedParallelism(1)

    /** True when [url] is already saved. */
    suspend fun isBookmarked(url: String): Boolean = withContext(dbContext) {
        if (!isBookmarkable(url)) return@withContext false
        helper.readableDatabase
            .query(TABLE, arrayOf("_id"), "url = ?", arrayOf(url), null, null, null, "1")
            .use { it.moveToFirst() }
    }

    /**
     * Star / unstar [url]. Returns the state the page ended up in, so the caller
     * can flip its icon without a second query.
     */
    suspend fun toggle(url: String, title: String): Boolean = withContext(dbContext) {
        if (!isBookmarkable(url)) return@withContext false
        val db = helper.writableDatabase
        val removed = db.delete(TABLE, "url = ?", arrayOf(url))
        if (removed > 0) return@withContext false
        db.insertWithOnConflict(TABLE, null, rowOf(url, title, System.currentTimeMillis()),
            SQLiteDatabase.CONFLICT_REPLACE)
        true
    }

    /** Newest first. [query] filters on URL or title when non-blank. */
    suspend fun all(query: String = ""): List<Bookmark> = withContext(dbContext) {
        val trimmed = query.trim()
        val (where, args) = if (trimmed.isEmpty()) {
            null to emptyArray<String>()
        } else {
            val like = "%$trimmed%"
            "url LIKE ? OR title LIKE ?" to arrayOf(like, like)
        }
        helper.readableDatabase
            .query(TABLE, null, where, args, null, null, "created_at DESC", LIMIT.toString())
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Bookmark(
                                id = c.getLong(c.getColumnIndexOrThrow("_id")),
                                url = c.getString(c.getColumnIndexOrThrow("url")),
                                title = c.getString(c.getColumnIndexOrThrow("title")).orEmpty(),
                                host = c.getString(c.getColumnIndexOrThrow("host")).orEmpty(),
                                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                            )
                        )
                    }
                }
            }
    }

    suspend fun delete(id: Long) = withContext(dbContext) {
        helper.writableDatabase.delete(TABLE, "_id = ?", arrayOf(id.toString()))
        Unit
    }

    suspend fun clearAll() = withContext(dbContext) {
        helper.writableDatabase.delete(TABLE, null, null)
        Unit
    }

    suspend fun count(): Int = withContext(dbContext) {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * Fold a remote set into the local table — the pull half of Drive sync.
     *
     * Union, never subtraction: a URL missing from [incoming] means "the other
     * device hasn't got it", not "the user deleted it". Without a tombstone log
     * those two are indistinguishable, and guessing wrong silently destroys
     * bookmarks. The cost is that unstarring on one device doesn't propagate;
     * that is the right way round to be wrong.
     *
     * On collision the earlier [Bookmark.createdAt] wins, so a page keeps the
     * moment it was first saved rather than jumping to the top of the list on
     * every sync.
     */
    suspend fun mergeIn(incoming: List<Bookmark>) = withContext(dbContext) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            incoming.forEach { bookmark ->
                if (!isBookmarkable(bookmark.url)) return@forEach
                val updated = db.compileStatement(
                    """
                    UPDATE $TABLE
                       SET title = COALESCE(NULLIF(?, ''), title),
                           created_at = MIN(created_at, ?)
                     WHERE url = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.bindString(1, bookmark.title)
                    stmt.bindLong(2, bookmark.createdAt)
                    stmt.bindString(3, bookmark.url)
                    stmt.executeUpdateDelete()
                }
                if (updated == 0) {
                    db.insertWithOnConflict(
                        TABLE, null,
                        rowOf(bookmark.url, bookmark.title, bookmark.createdAt),
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- Internals ---------------------------------------------------------

    private fun rowOf(url: String, title: String, createdAt: Long) = ContentValues().apply {
        put("url", url)
        put("title", title)
        put("host", HostTile.hostOf(url))
        put("created_at", createdAt)
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
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX idx_${TABLE}_url ON $TABLE(url)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 is the first shipped schema; nothing to migrate from yet.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "upgrid_bookmarks.db"
        private const val DB_VERSION = 1
        private const val TABLE = "bookmarks"

        /** Far past any hand-curated list; the UI has no pagination. */
        private const val LIMIT = 1000

        /** How many bookmarks the speed-dial shows before falling back to SEED. */
        const val SPEED_DIAL_SLOTS = 8

        /** Chrome URLs (about:, data:, the synthetic home page) can't be saved. */
        fun isBookmarkable(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://")
    }
}
