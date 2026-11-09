package app.lawnchair.deck

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.annotation.WorkerThread
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.provider.LauncherDbUtils.tableExists
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import com.android.launcher3.util.IntArray
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking

/**
 * Logical, transactional layout snapshots in the same database as Favorites.
 *
 * Snapshot tables deliberately belong to Lawnchair, rather than the AOSP database schema. A
 * snapshot from a different schema version is kept but never restored without a future migration.
 */
class DeckLayoutStore(private val context: Context, private val controller: ModelDbController) {
    @WorkerThread
    fun switchTo(currentDeck: Boolean, targetDeck: Boolean) {
        if (currentDeck == targetDeck) return
        controller.newTransaction().use { transaction ->
            val db = transaction.db
            createMetadata(db)
            val target = readSnapshot(db, targetDeck)
            // Validate before saving/deleting anything; all later operations also roll back together.
            if (target != null) validateSnapshot(db, targetDeck, target)
            saveSnapshot(db, currentDeck)
            if (target != null) restoreSnapshot(db, targetDeck)
            db.delete(STATE_TABLE, "id = 1", null)
            val state = ContentValues().apply {
                put("id", 1)
                put("enabled", if (targetDeck) 1 else 0)
                if (target == null) putNull("screen_order") else put("screen_order", target.screenOrder)
            }
            db.insertOrThrow(STATE_TABLE, null, state)
            transaction.commit()
        }
    }

    /** Remove private-profile placements from Deck only, retaining the original desktop layout. */
    @WorkerThread
    fun removePrivateItems(ids: Set<Int>) {
        if (ids.isEmpty() || !isDeckActive(context, controller)) return
        controller.newTransaction().use { transaction ->
            val db = transaction.db
            createMetadata(db)
            if (readSnapshot(db, false) == null) saveSnapshot(db, false)
            controller.delete("${Favorites._ID} IN (${ids.joinToString(",")})", null)
            // Legacy Deck had no logical mode marker. Its original layout is now safely retained.
            if (DatabaseUtils.queryNumEntries(db, STATE_TABLE, "id = 1", null) == 0L) {
                db.insertOrThrow(
                    STATE_TABLE,
                    null,
                    ContentValues().apply {
                        put("id", 1)
                        put("enabled", 1)
                        putNull("screen_order")
                    },
                )
            }
            transaction.commit()
        }
    }

    private fun createMetadata(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $LAYOUTS_TABLE (" +
                "layout TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, " +
                "columns_signature TEXT NOT NULL, screen_order TEXT NOT NULL, row_count INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $STATE_TABLE (" +
                "id INTEGER PRIMARY KEY, enabled INTEGER NOT NULL, screen_order TEXT)",
        )
    }

    private fun saveSnapshot(db: SQLiteDatabase, deck: Boolean) {
        val table = snapshotTable(deck)
        val columns = columns(db, Favorites.TABLE_NAME)
        db.execSQL("DROP TABLE IF EXISTS $table")
        db.execSQL(
            "CREATE TABLE $table AS SELECT ${columns.joinToString(",") { quote(it) }} " +
                "FROM ${Favorites.TABLE_NAME}",
        )
        val values = ContentValues().apply {
            put("layout", layoutName(deck))
            put("schema_version", db.version)
            put("columns_signature", columns.joinToString(","))
            put("screen_order", LauncherPrefs.get(context).get(LauncherPrefs.WORKSPACE_SCREEN_ORDER))
            put("row_count", DatabaseUtils.queryNumEntries(db, table))
        }
        db.insertWithOnConflict(LAYOUTS_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE).also {
            check(it != -1L) { "Could not save the current home screen layout." }
        }
    }

    private fun readSnapshot(db: SQLiteDatabase, deck: Boolean): Snapshot? {
        db.query(
            LAYOUTS_TABLE,
            arrayOf("schema_version", "columns_signature", "screen_order", "row_count"),
            "layout = ?",
            arrayOf(layoutName(deck)),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                check(!tableExists(db, snapshotTable(deck))) { "The saved layout is incomplete." }
                return null
            }
            return Snapshot(cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))
        }
    }

    private fun validateSnapshot(db: SQLiteDatabase, deck: Boolean, snapshot: Snapshot) {
        val table = snapshotTable(deck)
        check(tableExists(db, table)) { "The saved layout is missing." }
        val currentColumns = columns(db, Favorites.TABLE_NAME)
        check(snapshot.schemaVersion == db.version && snapshot.columns == currentColumns.joinToString(",")) {
            "The saved layout uses an older database schema. The current layout has been kept."
        }
        check(columns(db, table) == currentColumns) { "The saved layout has an incompatible schema." }
        check(DatabaseUtils.queryNumEntries(db, table) == snapshot.rowCount) { "The saved layout is incomplete." }
        db.rawQuery(
            "SELECT COUNT(*), COUNT(DISTINCT ${Favorites._ID}) FROM $table",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == cursor.getLong(1)) {
                "The saved layout has invalid item identifiers."
            }
        }
    }

    private fun restoreSnapshot(db: SQLiteDatabase, deck: Boolean) {
        // Use the controller so DatabaseHelper checks restored IDs and advances its item-ID counter.
        controller.delete(null, null)
        db.query(snapshotTable(deck), null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues(cursor.columnCount)
                cursor.columnNames.forEachIndexed { index, name ->
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                        Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(index))
                        Cursor.FIELD_TYPE_STRING -> values.put(name, cursor.getString(index))
                        Cursor.FIELD_TYPE_BLOB -> values.put(name, cursor.getBlob(index))
                    }
                }
                check(controller.insert(values) >= 0) { "Could not restore the saved home screen layout." }
            }
        }
    }

    private data class Snapshot(val schemaVersion: Int, val columns: String, val screenOrder: String, val rowCount: Long)

    companion object {
        private const val LAYOUTS_TABLE = "lawn_deck_layouts"
        private const val STATE_TABLE = "lawn_deck_state"
        private const val TAG = "DeckLayoutStore"

        private fun layoutName(deck: Boolean) = if (deck) "deck" else "desktop"
        private fun snapshotTable(deck: Boolean) = "lawn_deck_${layoutName(deck)}_favorites"
        private fun quote(identifier: String) = "\"${identifier.replace("\"", "\"\"")}\""

        private fun columns(db: SQLiteDatabase, table: String): List<String> = db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
            buildList {
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }

        /** A full launcher database reset must not resurrect an inactive home layout later. */
        @JvmStatic
        @WorkerThread
        fun clearSnapshots(db: SQLiteDatabase) {
            listOf(snapshotTable(false), snapshotTable(true), LAYOUTS_TABLE, STATE_TABLE).forEach { table ->
                db.execSQL("DROP TABLE IF EXISTS $table")
            }
        }

        /**
         * SQLite is authoritative after a switch. Reconcile preferences after a process interruption
         * between the SQLite commit and the preference write. Call on the model executor.
         */
        @JvmStatic
        @JvmOverloads
        @WorkerThread
        fun isDeckActive(context: Context, controller: ModelDbController, reloadOnRecovery: Boolean = true): Boolean {
            val prefs = PreferenceManager2.getInstance(context)
            val db = controller.db
            if (!tableExists(db, STATE_TABLE)) return prefs.deckLayout.firstBlocking()
            var pendingScreenOrder: String? = null
            var recovered = false
            val enabled = db.query(STATE_TABLE, null, "id = 1", null, null, null, null).use { cursor ->
                if (!cursor.moveToFirst()) return prefs.deckLayout.firstBlocking()
                val screenOrder = cursor.getColumnIndexOrThrow("screen_order")
                if (!cursor.isNull(screenOrder)) {
                    pendingScreenOrder = cursor.getString(screenOrder)
                }
                cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0
            }
            pendingScreenOrder?.let { order ->
                val launcherPrefs = LauncherPrefs.get(context)
                val changed = launcherPrefs.get(LauncherPrefs.WORKSPACE_SCREEN_ORDER) != order
                launcherPrefs.putSync(LauncherPrefs.WORKSPACE_SCREEN_ORDER.to(order))
                val values = ContentValues().apply { putNull("screen_order") }
                db.update(STATE_TABLE, values, "id = 1", null)
                recovered = changed
            }
            if (prefs.deckLayout.firstBlocking() != enabled) {
                prefs.deckLayout.setBlocking(enabled)
                recovered = true
            }
            if (recovered && reloadOnRecovery) {
                // LooperExecutor.execute runs inline on its own thread. post must defer this
                // reload until LoaderTask ends, so recovery cannot cancel its own loader.
                MODEL_EXECUTOR.post { LauncherAppState.getInstance(context).model.forceReload() }
            }
            return enabled
        }

        /** Existing logical snapshots; shared by widget and pinned-shortcut ownership checks. */
        @JvmStatic
        @WorkerThread
        fun savedFavoritesTables(db: SQLiteDatabase): List<String> = listOf(false, true).map(::snapshotTable).filter { tableExists(db, it) }

        /** Keep widget host bindings belonging to the inactive layout during ghost-widget cleanup. */
        @JvmStatic
        @WorkerThread
        fun getSavedWidgetIds(db: SQLiteDatabase): IntArray {
            val result = IntArray()
            savedFavoritesTables(db).forEach { table ->
                // A snapshot can be from an older schema; only inspect the two needed columns.
                runCatching {
                    db.query(
                        table,
                        arrayOf(Favorites.APPWIDGET_ID),
                        "${Favorites.ITEM_TYPE} = ?",
                        arrayOf(Favorites.ITEM_TYPE_APPWIDGET.toString()),
                        null,
                        null,
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) result.add(cursor.getInt(0))
                    }
                }.onFailure { Log.w(TAG, "Could not read saved widget bindings", it) }
            }
            return result
        }
    }
}
