package app.lawnchair.deck

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.provider.LauncherDbUtils.tableExists
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeckLayoutStoreTest {
    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private lateinit var controller: ModelDbController
    private lateinit var store: DeckLayoutStore

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = SQLiteDatabase.create(null)
        db.version = 32
        db.execSQL(
            "CREATE TABLE favorites (_id INTEGER PRIMARY KEY, title TEXT, intent TEXT, " +
                "container INTEGER, screen INTEGER, cellX INTEGER, cellY INTEGER, spanX INTEGER, " +
                "spanY INTEGER, itemType INTEGER, appWidgetId INTEGER NOT NULL DEFAULT -1, " +
                "icon BLOB, profileId INTEGER, rank INTEGER NOT NULL DEFAULT 0)",
        )
        controller = ModelDbController(db)
        store = DeckLayoutStore(context, controller)
        PreferenceManager2.instance.deckLayout.value = false
        LauncherPrefs.instance.screenOrder = "0,2"
        LauncherAppState.instance.model.reloads = 0
        MODEL_EXECUTOR.clear()
        insert(5, "Work folder", type = 2)
        insert(6, "Work app", container = 5, profile = 10)
        insert(7, "Personal app")
        insert(8, "Clock", type = 4, widgetId = 88)
    }

    @After
    fun close() = db.close()

    @Test
    fun firstDeckEntryPreservesTheWholeCurrentLayout() {
        val initial = rows()
        store.switchTo(false, true)
        assertEquals(initial, rows())
        assertEquals(initial, rows(DESKTOP))
        assertTrue(DeckLayoutStore.isDeckActive(context, controller))
        assertTrue(PreferenceManager2.instance.deckLayout.value)
    }

    @Test
    fun roundTripRestoresFoldersProfileSerialsBlobsWidgetsAndScreenOrder() {
        val desktop = rows()
        store.switchTo(false, true)
        DeckLayoutStore.isDeckActive(context, controller)
        db.execSQL("UPDATE favorites SET container=-101, title='Deck work app' WHERE _id=6")
        insert(25, "New deck app", profile = 11)
        LauncherPrefs.instance.screenOrder = "3,4"
        val deck = rows()

        store.switchTo(true, false)
        assertEquals(desktop, rows())
        assertFalse(DeckLayoutStore.isDeckActive(context, controller))
        assertEquals("0,2", LauncherPrefs.instance.screenOrder)
        assertFalse(PreferenceManager2.instance.deckLayout.value)

        store.switchTo(false, true)
        assertEquals(deck, rows())
        assertTrue(DeckLayoutStore.isDeckActive(context, controller))
        assertEquals("3,4", LauncherPrefs.instance.screenOrder)
        assertEquals(25L, controller.highestInsertedId)
    }

    @Test
    fun missingSnapshotTableCannotReplaceTheActiveLayoutOrMode() {
        store.switchTo(false, true)
        db.execSQL("DROP TABLE $DESKTOP")
        assertSwitchIsRejected()
    }

    @Test
    fun incompleteSnapshotCannotReplaceTheActiveLayoutOrMode() {
        store.switchTo(false, true)
        db.execSQL("DELETE FROM $DESKTOP WHERE _id=6")
        assertSwitchIsRejected()
    }

    @Test
    fun duplicateIdsAreRejectedEvenIfSavedCountMatches() {
        store.switchTo(false, true)
        db.execSQL("INSERT INTO $DESKTOP SELECT * FROM $DESKTOP WHERE _id=6")
        db.execSQL("UPDATE lawn_deck_layouts SET row_count=row_count+1 WHERE layout='desktop'")
        assertSwitchIsRejected()
    }

    @Test
    fun oldSchemaVersionDoesNotOverwriteTheActiveLayout() {
        store.switchTo(false, true)
        db.execSQL("UPDATE lawn_deck_layouts SET schema_version=31 WHERE layout='desktop'")
        assertSwitchIsRejected()
    }

    @Test
    fun newColumnDoesNotSilentlyShiftOldSnapshotData() {
        store.switchTo(false, true)
        db.execSQL("ALTER TABLE favorites ADD COLUMN futureColumn TEXT")
        assertSwitchIsRejected()
    }

    @Test
    fun insertFailureRollsBackDeletionPartialRestoreSnapshotSaveAndModeMarker() {
        store.switchTo(false, true)
        db.delete("favorites", null, null)
        insert(30, "Only active Deck item")
        controller.failInsertId = 6
        assertSwitchIsRejected()
        assertFalse(tableExists(db, DECK))
    }

    @Test
    fun committedDatabaseMarkerRepairsStalePreferenceInBothDirections() {
        store.switchTo(false, true)
        assertFalse(PreferenceManager2.instance.deckLayout.value)
        assertTrue(DeckLayoutStore.isDeckActive(context, controller))
        assertTrue(PreferenceManager2.instance.deckLayout.value)
        store.switchTo(true, false)
        assertTrue(PreferenceManager2.instance.deckLayout.value)
        assertFalse(DeckLayoutStore.isDeckActive(context, controller))
        assertFalse(PreferenceManager2.instance.deckLayout.value)
    }

    @Test
    fun pendingScreenOrderRecoveryRequestsOnlyOneReload() {
        store.switchTo(false, true)
        DeckLayoutStore.isDeckActive(context, controller)
        MODEL_EXECUTOR.drain()
        LauncherAppState.instance.model.reloads = 0
        LauncherPrefs.instance.screenOrder = "4,3"
        store.switchTo(true, false)
        assertEquals("4,3", LauncherPrefs.instance.screenOrder)
        DeckLayoutStore.isDeckActive(context, controller)
        assertEquals("0,2", LauncherPrefs.instance.screenOrder)
        assertEquals(0, LauncherAppState.instance.model.reloads)
        MODEL_EXECUTOR.drain()
        assertEquals(1, LauncherAppState.instance.model.reloads)
        DeckLayoutStore.isDeckActive(context, controller)
        MODEL_EXECUTOR.drain()
        assertEquals(1, LauncherAppState.instance.model.reloads)
    }

    @Test
    fun staleModeRecoveryDefersReloadUntilTheCurrentLoaderHasEnded() {
        store.switchTo(false, true)
        assertTrue(DeckLayoutStore.isDeckActive(context, controller))
        assertEquals(0, LauncherAppState.instance.model.reloads)
        MODEL_EXECUTOR.drain()
        assertEquals(1, LauncherAppState.instance.model.reloads)
        DeckLayoutStore.isDeckActive(context, controller)
        MODEL_EXECUTOR.drain()
        assertEquals(1, LauncherAppState.instance.model.reloads)
    }

    @Test
    fun widgetsFromBothSavedLayoutsRemainOwnedUntilSnapshotsAreReset() {
        store.switchTo(false, true)
        db.delete("favorites", "_id=8", null)
        insert(40, "Deck widget", type = 4, widgetId = 99)
        store.switchTo(true, false)
        assertEquals(setOf(88, 99), DeckLayoutStore.getSavedWidgetIds(db).toArray().toSet())
        val active = rows()
        controller.newTransaction().use {
            DeckLayoutStore.clearSnapshots(db)
            it.commit()
        }
        assertTrue(DeckLayoutStore.getSavedWidgetIds(db).toArray().isEmpty())
        assertEquals(active, rows())
        listOf(DESKTOP, DECK, "lawn_deck_state", "lawn_deck_layouts").forEach {
            assertFalse(tableExists(db, it))
        }
    }

    @Test
    fun snapshotResetParticipatesInTheCallersTransaction() {
        store.switchTo(false, true)
        controller.newTransaction().use { DeckLayoutStore.clearSnapshots(db) }
        assertTrue(tableExists(db, DESKTOP))
        assertTrue(DeckLayoutStore.isDeckActive(context, controller))
    }

    @Test
    fun legacyPrivateCleanupRetainsTheOriginalDesktopSnapshotAndFreesActiveCells() {
        insert(50, "Private app", profile = 20)
        val original = rows()
        PreferenceManager2.instance.deckLayout.value = true
        store.removePrivateItems(setOf(50))
        assertEquals(original, rows(DESKTOP))
        assertEquals(4, rows().size)
        assertTrue(DeckLayoutStore.isDeckActive(context, controller))
        assertEquals(1, count("lawn_deck_state", "id = 1 AND enabled = 1"))
        assertEquals(0, count("favorites", "_id=50"))
    }

    @Test
    fun privateCleanupDoesNotOverwriteAnExistingDesktopSnapshotOrModeMarker() {
        val desktop = rows()
        store.switchTo(false, true)
        insert(50, "Private app", profile = 20)
        store.removePrivateItems(setOf(50))
        assertEquals(desktop, rows(DESKTOP))
        assertEquals(0, count("favorites", "_id=50"))
        assertEquals(1, count("lawn_deck_state", "id = 1 AND enabled = 1"))
        store.switchTo(true, false)
        assertEquals(desktop, rows())
    }

    @Test
    fun privateCleanupNeverChangesTheNormalDesktop() {
        insert(50, "Private app", profile = 20)
        val desktop = rows()
        store.removePrivateItems(setOf(50))
        assertEquals(desktop, rows())
        assertFalse(tableExists(db, DESKTOP))
        assertFalse(tableExists(db, "lawn_deck_state"))
    }

    private fun count(table: String, where: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun assertSwitchIsRejected() {
        val active = rows()
        assertThrows(IllegalStateException::class.java) { store.switchTo(true, false) }
        assertEquals(active, rows())
        assertTrue(DeckLayoutStore.isDeckActive(context, controller))
    }

    private fun insert(id: Int, title: String, type: Int = 0, container: Int = -100, profile: Int = 0, widgetId: Int = -1) {
        val values = ContentValues().apply {
            put("_id", id)
            put("title", title)
            put("intent", "intent:#Intent;component=example/.Activity$id;end")
            put("container", container)
            put("screen", 2)
            put("cellX", 1)
            put("cellY", 2)
            put("spanX", 1)
            put("spanY", 1)
            put("itemType", type)
            put("appWidgetId", widgetId)
            put("icon", byteArrayOf(0, 1, -1, 127))
            put("profileId", profile)
            put("rank", 2)
        }
        db.insertOrThrow("favorites", null, values)
    }

    private fun rows(table: String = "favorites"): List<List<String>> =
        db.query(table, null, null, null, null, null, "_id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.columnNames.indices.map { index ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString(",")
                            else -> cursor.getString(index)
                        }
                    })
                }
            }
        }

    companion object {
        private const val DESKTOP = "lawn_deck_desktop_favorites"
        private const val DECK = "lawn_deck_deck_favorites"
    }
}
