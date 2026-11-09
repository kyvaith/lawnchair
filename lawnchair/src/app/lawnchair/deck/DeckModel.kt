package app.lawnchair.deck

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.annotation.WorkerThread
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT
import com.android.launcher3.LauncherSettings.Favorites.DESKTOP_ICON_FLAG
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.model.WorkspaceItemSpaceFinder
import com.android.launcher3.model.data.AppPairInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE
import com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_NOT_PINNABLE
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import com.android.launcher3.util.IntArray
import com.android.launcher3.util.IntSparseArrayMap

/** Owns Deck application membership; Favorites stores each application's native placement. */
object DeckModel {
    /** Exclude Private Space before the first workspace bind, including legacy Deck placements. */
    @WorkerThread
    fun filterPrivateItems(context: Context, controller: ModelDbController, loadedItems: IntSparseArrayMap<ItemInfo>) {
        val users = UserCache.INSTANCE.get(context)
        val privateUsers = users.userProfiles.filter { users.getUserInfo(it).isPrivate }.toSet()
        val blockedIds = loadedItems.filter { it.user in privateUsers }.map { it.id }.toMutableSet()
        loadedItems.filterIsInstance<AppPairInfo>().forEach { pair ->
            if (pair.getAppContents().any { it.user in privateUsers }) {
                blockedIds.add(pair.id)
                blockedIds.addAll(pair.getAppContents().map { it.id })
            }
        }
        loadedItems.filterIsInstance<FolderInfo>().forEach { folder ->
            if (folder.getContents().isNotEmpty() && folder.getContents().all { it.id in blockedIds }) {
                blockedIds.add(folder.id)
            }
        }
        // Hidden rows must not occupy cells that the visible workspace now considers available.
        // Retain the original desktop snapshot before removing them from the active Deck layout.
        DeckLayoutStore(context, controller).removePrivateItems(blockedIds)
        loadedItems.filterIsInstance<FolderInfo>().forEach { folder ->
            folder.getContents().removeAll { it.id in blockedIds }
        }
        blockedIds.forEach { loadedItems.remove(it) }
    }

    /** The loader has not committed yet when ModelDelegate.modelLoadComplete is called. */
    fun afterLoad(model: LauncherModel) {
        MODEL_EXECUTOR.post {
            model.enqueueModelUpdateTask(::reconcile)
        }
    }

    @WorkerThread
    @JvmStatic
    fun reconcile(controller: ModelTaskController, dataModel: BgDataModel, apps: AllAppsList) {
        val context = controller.context
        if (!DeckLayoutStore.isDeckActive(context, controller.model.modelDbController)) return

        val userCache = UserCache.INSTANCE.get(context)
        val users = userCache.userProfiles.toSet()
        val userManager = context.getSystemService(UserManager::class.java) ?: return
        val hidden = PreferenceManager2.getInstance(context).hiddenApps.firstCached()
        val catalog = apps.data.filter { app ->
            app.componentName != null && app.user in users &&
                !userCache.getUserInfo(app.user).isPrivate &&
                app.runtimeStatusFlags and (FLAG_NOT_PINNABLE or FLAG_INSTALL_SESSION_ACTIVE) == 0 &&
                app.toComponentKey().toString() !in hidden &&
                runCatching {
                    userManager.isUserUnlocked(app.user) && !userManager.isQuietModeEnabled(app.user)
                }.getOrDefault(false)
        }.associateBy { it.toComponentKey() }

        synchronized(dataModel) {
            // App pairs are actions containing two targets, not an application's primary placement.
            // Deep shortcuts, widgets and folders never participate in application deduplication.
            val placements = dataModel.itemsIdMap.filterIsInstance<WorkspaceItemInfo>().filter {
                it.itemType == ITEM_TYPE_APPLICATION && it.targetComponent != null &&
                    (
                        it.container == CONTAINER_DESKTOP || it.container == CONTAINER_HOTSEAT ||
                            dataModel.itemsIdMap[it.container] is FolderInfo
                        )
            }
            val plan = DeckReconciler.plan(
                catalog.keys,
                placements.map { DeckReconciler.Entry(it.id, ComponentKey(it.targetComponent, it.user)) },
            )
            if (plan.duplicateIds.isNotEmpty()) {
                val duplicates = placements.filter { it.id in plan.duplicateIds }
                controller.getModelWriter().deleteItemsFromDatabase(duplicates, "Deck duplicate application placement")
                // FolderInfo contents are shared with the UI. A normal loader rebuilds those
                // references and empty folders even when there are currently no bound callbacks.
                // Avoid editing them on the model thread or relying on a UI deletion callback.
                controller.model.forceReload()
                return
            }
            if (plan.missing.isEmpty()) return

            val writer = controller.getModelWriter()
            val finder = WorkspaceItemSpaceFinder(
                dataModel,
                InvariantDeviceProfile.INSTANCE.get(context),
                controller.model,
            )
            val screens = dataModel.itemsIdMap.collectWorkspaceScreens(context)
            val addedScreens = IntArray()
            val added = ArrayList<ItemInfo>()
            plan.missing.forEach { key ->
                val app = catalog.getValue(key)
                val item = app.makeWorkspaceItem(context)
                controller.iconCache.getTitleAndIcon(item, DESKTOP_ICON_FLAG)
                val position = finder.findSpaceForItem(screens, addedScreens, added, 1, 1, context)
                writer.updateItemInfoProps(item, CONTAINER_DESKTOP, position[0], position[1], position[2])
                // Native screen IDs use MAX(screen) from Favorites. Commit this placement on
                // MODEL_EXECUTOR before the next lookup can allocate another workspace screen.
                writer.addItemsToDatabase(listOf(item))
                added.add(item)
            }
            // Do not use AddWorkspaceItemsTask/ItemInstallQueue: those are optional shortcut
            // installation paths and intentionally skip some system apps. Deck owns all apps.
            controller.scheduleCallbackTask { it.bindItemsAdded(added) }
            Log.d("DeckModel", "Added ${added.size} application placements")
        }
    }
}
