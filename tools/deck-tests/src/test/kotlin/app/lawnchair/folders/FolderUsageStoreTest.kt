package app.lawnchair.folders

import android.content.Context
import android.database.sqlite.SQLiteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class FolderUsageStoreTest {
    private lateinit var context: Context
    private lateinit var store: FolderUsageStore

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(FolderUsageStore.DATABASE_NAME)
        store = FolderUsageStore(context)
    }

    @After
    fun close() = store.close()

    @Test
    fun countersRemainSeparateForComponentAliasesAndProfileSerialsAndSurviveReopen() {
        val personal = "example/.Main/0"
        val work = "example/.Main/107"
        val alias = "example/.Alias/0"
        assertEquals(1L, store.increment(personal))
        assertEquals(2L, store.increment(personal))
        assertEquals(1L, store.increment(work))
        store.close()
        store = FolderUsageStore(context)
        assertEquals(mapOf(personal to 2L, work to 1L, alias to 0L), store.counts(listOf(personal, work, alias)))
    }

    @Test
    fun counterSaturatesAtLongMaxWithoutBecomingAFloatOrNegative() {
        val key = "example/.Main/0"
        store.increment(key)
        store.close()
        context.openOrCreateDatabase(FolderUsageStore.DATABASE_NAME, 0, null).use {
            it.execSQL("UPDATE folder_usage SET launches=? WHERE usage_key=?", arrayOf<Any>(Long.MAX_VALUE - 1, key))
        }
        store = FolderUsageStore(context)
        assertEquals(Long.MAX_VALUE, store.increment(key))
        assertEquals(Long.MAX_VALUE, store.increment(key))
        context.openOrCreateDatabase(FolderUsageStore.DATABASE_NAME, 0, null).use {
            it.rawQuery("SELECT typeof(launches) FROM folder_usage WHERE usage_key=?", arrayOf(key)).use { cursor ->
                cursor.moveToFirst()
                assertEquals("integer", cursor.getString(0))
            }
        }
    }

    @Test
    fun failedIncrementRollsBackTheNewIdentityAndPreservesExistingCounts() {
        store.increment("valid")
        context.openOrCreateDatabase(FolderUsageStore.DATABASE_NAME, 0, null).use {
            it.execSQL("CREATE TRIGGER fail_counter BEFORE UPDATE ON folder_usage WHEN NEW.usage_key='broken' BEGIN SELECT RAISE(ABORT, 'simulated failure'); END")
        }
        assertThrows(SQLiteException::class.java) { store.increment("broken") }
        assertEquals(mapOf("valid" to 1L, "broken" to 0L), store.counts(listOf("valid", "broken")))
        context.openOrCreateDatabase(FolderUsageStore.DATABASE_NAME, 0, null).use {
            it.rawQuery("SELECT COUNT(*) FROM folder_usage WHERE usage_key='broken'", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun largeRequestedSetsAreChunkedAndUnknownIdentitiesAreZero() {
        val keys = (0 until 1200).map { "example/.Activity$it/0" }
        listOf(keys[0], keys[500], keys.last()).forEach { store.increment(it) }
        val result = store.counts(keys)
        assertEquals(1200, result.size)
        assertEquals(3L, result.values.sum())
    }

    @Test
    fun blankRequestsDoNotCreateStorageAndKeysAreBoundAsValues() {
        assertEquals(0L, store.increment(""))
        assertEquals(emptyMap<String, Long>(), store.counts(listOf("", " ")))
        assertFalse(context.getDatabasePath(FolderUsageStore.DATABASE_NAME).exists())
        val key = "example/'quoted; DROP TABLE folder_usage; --/107"
        store.increment(key)
        assertEquals(1L, store.counts(listOf(key))[key])
    }
}
