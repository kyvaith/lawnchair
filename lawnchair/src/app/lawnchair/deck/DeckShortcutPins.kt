package app.lawnchair.deck

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import com.android.launcher3.shortcuts.ShortcutKey

/** Inactive layouts still own their Android shortcut pins, just as they own widget bindings. */
object DeckShortcutPins {
    @JvmStatic
    @WorkerThread
    fun getSavedShortcuts(context: Context, user: UserHandle): Set<ShortcutKey> {
        val userCache = UserCache.INSTANCE.get(context)
        // getSerialNumberForUser falls back to main-user values for an unknown profile.
        if (user !in userCache.userProfiles) return emptySet()
        val serial = userCache.getSerialNumberForUser(user)
        val db = LauncherAppState.getInstance(context).model.modelDbController.db
        val result = LinkedHashSet<ShortcutKey>()
        DeckLayoutStore.savedFavoritesTables(db).forEach { table ->
            runCatching {
                db.query(
                    table,
                    arrayOf(Favorites.INTENT),
                    "${Favorites.ITEM_TYPE} = ? AND ${Favorites.PROFILE_ID} = ?",
                    arrayOf(Favorites.ITEM_TYPE_DEEP_SHORTCUT.toString(), serial.toString()),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.isNull(0)) continue
                        runCatching {
                            val intent = Intent.parseUri(cursor.getString(0), 0)
                            val id = intent.getStringExtra(ShortcutKey.EXTRA_SHORTCUT_ID)
                            if (!intent.`package`.isNullOrEmpty() && !id.isNullOrEmpty()) {
                                result.add(ShortcutKey.fromIntent(intent, user))
                            }
                        }.onFailure { Log.w(TAG, "Skipping an invalid saved shortcut", it) }
                    }
                }
            }.onFailure { Log.w(TAG, "Could not read shortcut pins from a saved layout", it) }
        }
        return result
    }

    private const val TAG = "DeckShortcutPins"
}
