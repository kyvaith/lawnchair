package app.lawnchair.folders

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.WorkerThread

/** Local launch counters are shared across grid databases and desktop/Deck layouts. */
class FolderUsageStore(context: Context) : AutoCloseable {
    private val helper = UsageDatabase(context.applicationContext)

    /** The caller supplies an exact launcher component + persistent profile-serial identity. */
    @WorkerThread
    fun increment(key: String): Long {
        if (key.isBlank()) return 0L
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            // Keep compatibility with SQLite versions on Android 8; no newer UPSERT syntax.
            db.execSQL("INSERT OR IGNORE INTO $TABLE_NAME (usage_key, launches) VALUES (?, 0)", arrayOf(key))
            db.execSQL(
                "UPDATE $TABLE_NAME SET launches = CASE WHEN launches < ${Long.MAX_VALUE} " +
                    "THEN launches + 1 ELSE ${Long.MAX_VALUE} END WHERE usage_key = ?",
                arrayOf(key),
            )
            val count = db.rawQuery("SELECT launches FROM $TABLE_NAME WHERE usage_key = ?", arrayOf(key)).use {
                check(it.moveToFirst()) { "The launch counter could not be saved." }
                it.getLong(0)
            }
            db.setTransactionSuccessful()
            count
        } finally {
            db.endTransaction()
        }
    }

    /** Reads only requested identities, returning zero for an identity with no recorded launches. */
    @WorkerThread
    fun counts(keys: Collection<String>): Map<String, Long> {
        val requested = keys.filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty()) return emptyMap()
        val result = requested.associateWith { 0L }.toMutableMap()
        val db = helper.readableDatabase
        requested.chunked(500).forEach { chunk ->
            db.query(
                TABLE_NAME,
                arrayOf("usage_key", "launches"),
                "usage_key IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray(),
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getLong(1).coerceAtLeast(0L)
            }
        }
        return result
    }

    @WorkerThread
    override fun close() = helper.close()

    private class UsageDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_NAME (usage_key TEXT PRIMARY KEY NOT NULL, " +
                    "launches INTEGER NOT NULL CHECK (launches >= 0))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Unsupported folder usage database version: $oldVersion to $newVersion")
        }
    }

    companion object {
        const val DATABASE_NAME = "lawnchair-folder-usage.db"
        private const val TABLE_NAME = "folder_usage"
    }
}
