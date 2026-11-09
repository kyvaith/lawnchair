package app.lawnchair.folders

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import java.util.Locale
import org.json.JSONArray

data class FolderSortState(val mode: FolderSortMode, val originalTokens: List<String>)

data class FolderSortStoreResult(
    val mode: FolderSortMode,
    val orderedEntries: List<FolderSortEntry>,
    val hasOriginal: Boolean,
)

/** Per-folder state belongs to the same grid database as Favorites, with separate layout scopes. */
class FolderSortStore(private val db: SQLiteDatabase) {
    @WorkerThread
    fun read(layout: String, folderId: Int): FolderSortState? {
        validateScope(layout, folderId)
        if (!tableExists(db)) return null
        return db.query(
            TABLE_NAME,
            arrayOf("mode", "original_tokens"),
            "layout = ? AND folder_id = ?",
            arrayOf(layout, folderId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            try {
                val mode = FolderSortMode.valueOf(cursor.getString(0))
                val tokens = JSONArray(cursor.getString(1))
                FolderSortState(mode, List(tokens.length()) { tokens.getString(it) })
            } catch (exception: Exception) {
                throw IllegalStateException("The saved folder order could not be read. Its current order has been kept.", exception)
            }
        }
    }

    /**
     * Computes and saves a sort operation. A caller can wrap this and Favorites rank updates in
     * one outer transaction; Android SQLite nested transactions roll back together.
     *
     * Null mode applies the saved automatic mode. Opening an ordinary/manual folder leaves its
     * current order untouched. An explicit MANUAL choice restores its retained original order.
     */
    @WorkerThread
    fun prepare(
        layout: String,
        folderId: Int,
        mode: FolderSortMode?,
        entries: List<FolderSortEntry>,
        counts: Map<String, Long>,
        locale: Locale,
    ): FolderSortStoreResult = transaction {
        val state = read(layout, folderId)
        val effectiveMode = mode ?: state?.mode ?: FolderSortMode.MANUAL
        if (effectiveMode == FolderSortMode.MANUAL && (mode == null || state == null)) {
            return@transaction FolderSortStoreResult(effectiveMode, entries.toList(), state != null)
        }
        val plan = FolderSortPolicy.plan(
            effectiveMode,
            entries,
            state?.originalTokens ?: entries.map { it.stableToken },
            counts,
            locale,
        )
        val updated = FolderSortState(effectiveMode, plan.originalTokens)
        if (state != updated) save(layout, folderId, updated)
        FolderSortStoreResult(effectiveMode, plan.orderedEntries, true)
    }

    /** A drag can select manual mode without restoring the old baseline over the user's move. */
    @WorkerThread
    fun setManual(layout: String, folderId: Int) = transaction {
        val state = read(layout, folderId) ?: return@transaction
        if (state.mode != FolderSortMode.MANUAL) save(layout, folderId, state.copy(mode = FolderSortMode.MANUAL))
    }

    private fun save(layout: String, folderId: Int, state: FolderSortState) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_NAME (" +
                "layout TEXT NOT NULL, folder_id INTEGER NOT NULL, mode TEXT NOT NULL, " +
                "original_tokens TEXT NOT NULL, PRIMARY KEY (layout, folder_id))",
        )
        val values = ContentValues().apply {
            put("layout", layout)
            put("folder_id", folderId)
            put("mode", state.mode.name)
            put("original_tokens", JSONArray(state.originalTokens).toString())
        }
        check(db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "The folder order could not be saved."
        }
    }

    private inline fun <T> transaction(action: () -> T): T {
        db.beginTransaction()
        return try {
            val result = action()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val TABLE_NAME = "lawnchair_folder_sort"

        private fun validateScope(layout: String, folderId: Int) {
            require(layout.isNotBlank()) { "The layout scope must not be empty." }
            require(folderId >= 0) { "Only persisted folders have a sort order." }
        }

        private fun tableExists(db: SQLiteDatabase): Boolean = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(TABLE_NAME)).use {
            it.moveToFirst()
        }

        /** Full launcher reset must also discard old folder identities and original orders. */
        @JvmStatic
        @WorkerThread
        fun clear(db: SQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        }

        /** A newly created folder must not inherit metadata from a previous user of the same ID. */
        @JvmStatic
        @WorkerThread
        fun forgetNewFolder(db: SQLiteDatabase, layout: String, folderId: Int) {
            validateScope(layout, folderId)
            if (tableExists(db)) {
                db.delete(TABLE_NAME, "layout = ? AND folder_id = ?", arrayOf(layout, folderId.toString()))
            }
        }
    }
}
