package app.lawnchair.deck

import android.content.Context
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherState
import com.android.launcher3.model.data.CollectionInfo
import com.android.launcher3.model.data.ItemInfo

/** UI rules for the single-layer workspace; the standard layout keeps Launcher3 behavior. */
object DeckUiPolicy {
    @JvmStatic
    fun isEnabled(context: Context): Boolean = PreferenceManager2.getInstance(context).deckLayout.firstCached()

    @JvmStatic
    fun canGoToState(context: Context, state: LauncherState): Boolean = !isEnabled(context) || state != LauncherState.ALL_APPS

    @JvmStatic
    fun canRemove(context: Context, item: ItemInfo): Boolean = item.id != ItemInfo.NO_ID &&
        (!isEnabled(context) || (item.itemType != ITEM_TYPE_APPLICATION && item !is CollectionInfo))

    @JvmStatic
    fun canAddToWorkspace(context: Context, item: ItemInfo): Boolean = !isEnabled(context) ||
        (item.itemType != ITEM_TYPE_APPLICATION && item !is CollectionInfo)
}
