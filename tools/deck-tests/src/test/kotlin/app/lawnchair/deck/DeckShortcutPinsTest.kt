package app.lawnchair.deck

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.UserHandle
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.pm.UserCache
import com.android.launcher3.shortcuts.ShortcutKey
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import org.junit.After
import org.junit.Assert.assertEquals
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
class DeckShortcutPinsTest {
    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private lateinit var store: DeckLayoutStore
    private val personal = UserHandle.getUserHandleForUid(0)
    private val work = UserHandle.getUserHandleForUid(1_000_000)

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = SQLiteDatabase.create(null)
        db.version = 32
        db.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY, itemType INTEGER, profileId INTEGER, intent TEXT)")
        val controller = ModelDbController(db)
        LauncherAppState.instance.model.modelDbController = controller
        store = DeckLayoutStore(context, controller)
        UserCache.instance.serials.clear()
        UserCache.instance.serials[personal] = 0
        UserCache.instance.serials[work] = 107
        PreferenceManager2.instance.deckLayout.value = false
        LauncherPrefs.instance.screenOrder = "0"
        MODEL_EXECUTOR.clear()
    }

    @After
    fun close() = db.close()

    @Test
    fun unionsAndDeduplicatesOwnedPinsAcrossBothSnapshotsByProfileSerial() {
        insert(1, 0, intent("personal"))
        insert(2, 107, intent("work"))
        store.switchTo(false, true)
        insert(3, 0, intent("deck"))
        insert(4, 0, intent("personal"))
        store.switchTo(true, false)
        assertEquals(setOf(key("personal", personal), key("deck", personal)), DeckShortcutPins.getSavedShortcuts(context, personal))
        assertEquals(setOf(key("work", work)), DeckShortcutPins.getSavedShortcuts(context, work))
    }

    @Test
    fun ignoresWrongSerialApplicationItemsAndMalformedOrIncompleteIntents() {
        insert(1, 0, intent("valid"))
        insert(2, 0, intent("application"), type = 0)
        insert(3, 10, intent("wrong_serial"))
        insert(4, 107, intent("work"))
        insert(5, 0, null)
        insert(6, 0, "intent:#Intent;S.shortcut_id=bad;broken")
        insert(7, 0, Intent(Intent.ACTION_MAIN).putExtra(ShortcutKey.EXTRA_SHORTCUT_ID, "no_package").toUri(0))
        insert(8, 0, Intent(Intent.ACTION_MAIN).setPackage("example").toUri(0))
        insert(9, 0, intent(""))
        store.switchTo(false, true)
        assertEquals(setOf(key("valid", personal)), DeckShortcutPins.getSavedShortcuts(context, personal))
        assertEquals(setOf(key("work", work)), DeckShortcutPins.getSavedShortcuts(context, work))
    }

    @Test
    fun unknownProfileDoesNotInheritPersonalProfilePinsFromSerialFallback() {
        insert(1, 0, intent("personal"))
        store.switchTo(false, true)
        assertTrue(DeckShortcutPins.getSavedShortcuts(context, UserHandle.getUserHandleForUid(9_900_000)).isEmpty())
    }

    @Test
    fun resetReleasesSnapshotOwnership() {
        insert(1, 0, intent("personal"))
        store.switchTo(false, true)
        assertEquals(1, DeckShortcutPins.getSavedShortcuts(context, personal).size)
        DeckLayoutStore.clearSnapshots(db)
        assertTrue(DeckShortcutPins.getSavedShortcuts(context, personal).isEmpty())
    }

    private fun key(id: String, user: UserHandle) = ShortcutKey("example", user, id)
    private fun intent(id: String) = Intent(Intent.ACTION_MAIN)
        .setPackage("example")
        .putExtra(ShortcutKey.EXTRA_SHORTCUT_ID, id)
        .toUri(0)

    private fun insert(id: Int, serial: Int, intent: String?, type: Int = 6) {
        db.insertOrThrow("favorites", null, ContentValues().apply {
            put("_id", id)
            put("profileId", serial)
            put("itemType", type)
            put("intent", intent)
        })
    }
}
