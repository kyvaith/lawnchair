package app.lawnchair.folders

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
class FolderSortStoreTest {
    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private lateinit var store: FolderSortStore
    private val original = listOf(entry(1, "Zulu"), entry(2, "Beta"), entry(3, "Alpha"))

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE)
        db = context.openOrCreateDatabase(DATABASE, 0, null)
        store = FolderSortStore(db)
    }

    @After
    fun close() = db.close()

    @Test
    fun ordinaryManualOpenLeavesTheCurrentOrderAndCreatesNoBaseline() {
        val result = prepare(null)
        assertEquals(original, result.orderedEntries)
        assertEquals(FolderSortMode.MANUAL, result.mode)
        assertFalse(result.hasOriginal)
        assertNull(store.read("desktop", 7))
    }

    @Test
    fun alphabeticThenUsageThenOriginalPreservesTheFirstBaselineAcrossStoreRecreation() {
        val alphabetical = prepare(FolderSortMode.ALPHABETICAL)
        assertEquals(listOf(3, 2, 1), alphabetical.orderedEntries.map { it.id })
        val path = db.path
        db.close()
        db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
        store = FolderSortStore(db)
        val used = prepare(FolderSortMode.MOST_USED, alphabetical.orderedEntries, mapOf("Beta" to 9L))
        assertEquals(listOf(2, 1, 3), used.orderedEntries.map { it.id })
        assertEquals(original.map { it.stableToken }, store.read("desktop", 7)!!.originalTokens)
        val restored = prepare(FolderSortMode.MANUAL, used.orderedEntries)
        assertEquals(original, restored.orderedEntries)
        assertTrue(restored.hasOriginal)
    }

    @Test
    fun savedAutomaticModeAppliesNewCountsOnTheNextOpen() {
        val first = prepare(FolderSortMode.MOST_USED, counts = mapOf("Alpha" to 4L))
        val next = prepare(null, first.orderedEntries, mapOf("Zulu" to 5L))
        assertEquals(FolderSortMode.MOST_USED, next.mode)
        assertEquals(original.map { it.id }, next.orderedEntries.map { it.id })
    }

    @Test
    fun selectingManualAfterADragDoesNotRestoreOverTheUsersNewPositions() {
        prepare(FolderSortMode.ALPHABETICAL)
        store.setManual("desktop", 7)
        val dragged = listOf(original[2], original[0], original[1])
        assertEquals(dragged, prepare(null, dragged).orderedEntries)
        assertEquals(original, prepare(FolderSortMode.MANUAL, dragged).orderedEntries)
    }

    @Test
    fun removedOriginalMembersStayRemovedAndNewOnesGetAnOriginalPositionAtTheEnd() {
        prepare(FolderSortMode.ALPHABETICAL)
        val changed = listOf(original[2], entry(4, "Delta"), original[0])
        prepare(null, changed)
        assertEquals(listOf(1, 3, 4), prepare(FolderSortMode.MANUAL, changed).orderedEntries.map { it.id })
    }

    @Test
    fun layoutFolderAndGridDatabaseScopesDoNotShareMetadata() {
        prepare(FolderSortMode.ALPHABETICAL)
        assertNull(store.read("deck", 7))
        assertNull(store.read("desktop", 8))
        store.prepare("deck", 7, FolderSortMode.MOST_USED, original.reversed(), emptyMap(), Locale.US)
        assertEquals(original.reversed().map { it.stableToken }, store.read("deck", 7)!!.originalTokens)
        assertEquals(original.map { it.stableToken }, store.read("desktop", 7)!!.originalTokens)
        SQLiteDatabase.create(null).use { otherGrid ->
            assertNull(FolderSortStore(otherGrid).read("desktop", 7))
        }
    }

    @Test
    fun aNewFolderReusingAnIdCannotInheritAnOldModeOrBaseline() {
        prepare(FolderSortMode.ALPHABETICAL)
        store.prepare("deck", 7, FolderSortMode.MOST_USED, original, emptyMap(), Locale.US)
        FolderSortStore.forgetNewFolder(db, "desktop", 7)
        assertNull(store.read("desktop", 7))
        assertEquals(FolderSortMode.MOST_USED, store.read("deck", 7)!!.mode)
        val newFolder = listOf(entry(11, "New B"), entry(12, "New A"))
        assertEquals(newFolder, prepare(null, newFolder).orderedEntries)
        assertFalse(prepare(FolderSortMode.MANUAL, newFolder).hasOriginal)
    }

    @Test
    fun outerTransactionRollsBackMetadataAndRanksAsOneOperation() {
        db.execSQL("CREATE TABLE items (_id INTEGER PRIMARY KEY, rank INTEGER)")
        db.execSQL("INSERT INTO items VALUES (1, 0), (2, 1), (3, 2)")
        db.beginTransaction()
        try {
            val result = prepare(FolderSortMode.ALPHABETICAL)
            result.orderedEntries.forEachIndexed { index, item ->
                db.execSQL("UPDATE items SET rank=? WHERE _id=?", arrayOf(index, item.id))
            }
            // Simulate a later failure while the caller is persisting folder positions.
        } finally {
            db.endTransaction()
        }
        assertNull(store.read("desktop", 7))
        db.rawQuery("SELECT _id FROM items ORDER BY rank", null).use { cursor ->
            val ids = buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
            assertEquals(listOf(1, 2, 3), ids)
        }
    }

    @Test
    fun fullResetClearsBothLayoutsAndParticipatesInItsOuterTransaction() {
        prepare(FolderSortMode.ALPHABETICAL)
        store.prepare("deck", 7, FolderSortMode.MOST_USED, original, emptyMap(), Locale.US)
        db.beginTransaction()
        try {
            FolderSortStore.clear(db)
        } finally {
            db.endTransaction()
        }
        assertTrue(store.read("desktop", 7) != null)
        FolderSortStore.clear(db)
        assertNull(store.read("desktop", 7))
        assertNull(store.read("deck", 7))
    }

    @Test
    fun corruptOriginalOrderAbortsTheOperationWithoutReplacingIt() {
        prepare(FolderSortMode.ALPHABETICAL)
        db.execSQL("UPDATE lawnchair_folder_sort SET original_tokens='bad-json'")
        assertThrows(IllegalStateException::class.java) { prepare(FolderSortMode.MOST_USED) }
        db.rawQuery("SELECT mode, original_tokens FROM lawnchair_folder_sort", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("ALPHABETICAL", it.getString(0))
            assertEquals("bad-json", it.getString(1))
        }
    }

    private fun prepare(mode: FolderSortMode?, entries: List<FolderSortEntry> = original, counts: Map<String, Long> = emptyMap()) =
        store.prepare("desktop", 7, mode, entries, counts, Locale.US)

    private fun entry(id: Int, label: String) = FolderSortEntry(id, "component/$id/profile0", label, label)

    companion object {
        private const val DATABASE = "folder-sort-runtime-tests.db"
    }
}
