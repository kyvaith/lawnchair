package app.lawnchair.folders

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import app.lawnchair.deck.DeckLayoutStore
import app.lawnchair.launcherNullable
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderGridOrganizer
import com.android.launcher3.model.data.AppPairInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.shortcuts.ShortcutKey
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import java.util.Locale
import java.util.WeakHashMap

data class FolderSortResult(
    val mode: FolderSortMode,
    val orderedIds: List<Int>,
    val hasOriginal: Boolean,
)

/** Coordinates immutable UI snapshots with transactional, model-thread folder writes. */
object FolderSortController {
    private val requests = WeakHashMap<Folder, Long>()
    private var nextRequest = 0L

    fun inspect(folder: Folder, completion: (FolderSortResult?) -> Unit) {
        dispatch(folder, null, inspectOnly = true, completion)
    }

    fun request(folder: Folder, mode: FolderSortMode? = null, completion: (FolderSortResult?) -> Unit) {
        dispatch(folder, mode, inspectOnly = false, completion)
    }

    /** A manual drag disables the automatic mode without overwriting the retained original. */
    fun useManualOrder(folder: Folder) {
        val snapshot = capture(folder) ?: return
        MODEL_EXECUTOR.post {
            runCatching {
                if (!isCurrent(snapshot)) return@runCatching
                val controller = snapshot.launcher.model.modelDbController
                val layout = layout(snapshot.launcher, controller.db)
                if (layout == snapshot.layout) FolderSortStore(controller.db).setManual(layout, snapshot.folderId)
            }.onFailure { Log.w(TAG, "Could not retain a manual folder order", it) }
        }
    }

    /** Called after the launcher's normal successful-start logging, independent of predictions. */
    fun recordLaunch(context: Context, item: ItemInfo) {
        if (item.itemType != Favorites.ITEM_TYPE_APPLICATION && item.itemType != Favorites.ITEM_TYPE_DEEP_SHORTCUT) return
        if (item is WorkspaceItemInfo && item.isPromise) return
        if (item is ItemInfoWithIcon && item.runtimeStatusFlags and
            (ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE or ItemInfoWithIcon.FLAG_NOT_PINNABLE) != 0
        ) {
            return
        }
        val users = UserCache.INSTANCE.get(context)
        if (item.user !in users.userProfiles || users.getUserInfo(item.user).isPrivate) return
        val serial = users.getSerialNumberForUser(item.user)
        val key = identity(item, serial) ?: return
        val appContext = context.applicationContext
        MODEL_EXECUTOR.post {
            runCatching {
                FolderUsageStore(appContext).use { it.increment(key) }
            }.onFailure { Log.w(TAG, "Could not record a local launch count", it) }
        }
    }

    private fun dispatch(
        folder: Folder,
        mode: FolderSortMode?,
        inspectOnly: Boolean,
        completion: (FolderSortResult?) -> Unit,
    ) {
        val snapshot = capture(folder)
        if (snapshot == null || (!inspectOnly && isLocked(snapshot.launcher))) {
            completion(null)
            return
        }
        val requestId = ++nextRequest
        requests[folder] = requestId
        MODEL_EXECUTOR.post {
            val outcome = runCatching {
                if (!isCurrent(snapshot)) return@runCatching null
                if (!inspectOnly && isLocked(snapshot.launcher)) return@runCatching null
                val controller = snapshot.launcher.model.modelDbController
                val db = controller.db
                if (layout(snapshot.launcher, db) != snapshot.layout) return@runCatching null
                val store = FolderSortStore(db)
                if (inspectOnly) {
                    val state = store.read(snapshot.layout, snapshot.folderId)
                    return@runCatching FolderSortResult(
                        state?.mode ?: FolderSortMode.MANUAL,
                        snapshot.entries.map { it.id },
                        state?.originalTokens?.isNotEmpty() == true,
                    )
                }
                // The state, original order and all native placement ranks commit or roll back
                // together. No model/UI objects are mutated from this worker thread.
                controller.newTransaction().use { transaction ->
                    if (!matchesDatabase(db, snapshot)) return@runCatching null
                    val effectiveMode = mode ?: store.read(snapshot.layout, snapshot.folderId)?.mode ?: FolderSortMode.MANUAL
                    // Alphabetical/Restore/manual folders do not depend on the optional usage DB.
                    val counts = if (effectiveMode == FolderSortMode.MOST_USED) {
                        FolderUsageStore(snapshot.launcher).use { usage ->
                            usage.counts(snapshot.entries.mapNotNull { it.usageKey })
                        }
                    } else {
                        emptyMap()
                    }
                    val result = store.prepare(
                        snapshot.layout,
                        snapshot.folderId,
                        mode,
                        snapshot.entries,
                        counts,
                        snapshot.locale,
                    )
                    val ordered = result.orderedEntries
                    if (ordered.map { it.id } != snapshot.entries.map { it.id }) {
                        val grid = FolderGridOrganizer(snapshot.columns, snapshot.rows).setContentSize(ordered.size)
                        ordered.forEachIndexed { rank, entry ->
                            val position = grid.getPosForRank(rank)
                            val values = ContentValues().apply {
                                put(Favorites.RANK, rank)
                                put(Favorites.CELLX, position.x)
                                put(Favorites.CELLY, position.y)
                            }
                            check(controller.update(values, "${Favorites._ID} = ? AND ${Favorites.CONTAINER} = ?", arrayOf(entry.id.toString(), snapshot.folderId.toString())) == 1) {
                                "The folder changed while sorting."
                            }
                        }
                    }
                    transaction.commit()
                    FolderSortResult(result.mode, ordered.map { it.id }, result.hasOriginal)
                }
            }
            MAIN_EXECUTOR.execute {
                val committed = outcome.getOrNull()
                if (!inspectOnly && committed != null && !applyCommittedOrder(folder, snapshot, committed)) {
                    // A close, recreation or model update must never leave a committed database
                    // order paired with the old canonical FolderInfo, especially after Restore.
                    if (isCurrent(snapshot)) snapshot.launcher.model.forceReload()
                    if (requests[folder] == requestId) {
                        requests.remove(folder)
                        completion(null)
                    }
                    return@execute
                }
                if (requests[folder] != requestId) return@execute
                requests.remove(folder)
                val current = capture(folder)
                if (current == null || current.loadId != snapshot.loadId || current.folderInfo !== snapshot.folderInfo ||
                    current.layout != snapshot.layout || current.entries.associate { it.id to it.stableToken } != snapshot.entries.associate { it.id to it.stableToken }
                ) {
                    completion(null)
                } else {
                    outcome.exceptionOrNull()?.let {
                        Log.e(TAG, "Could not sort the folder", it)
                        Toast.makeText(folder.context, R.string.folder_sort_failed, Toast.LENGTH_LONG).show()
                    }
                    completion(outcome.getOrNull())
                }
            }
        }
    }

    /** Keep canonical model state coherent even when the originating folder view has closed. */
    private fun applyCommittedOrder(folder: Folder, snapshot: Snapshot, result: FolderSortResult): Boolean {
        if (!isCurrent(snapshot) ||
            (if (PreferenceManager2.getInstance(snapshot.launcher).deckLayout.firstCached()) "deck" else "desktop") != snapshot.layout
        ) {
            return false
        }
        val items = snapshot.folderInfo.getContents()
        val byId = items.associateBy { it.id }
        if (byId.size != items.size || byId.keys != result.orderedIds.toSet()) return false
        val currentOrder = items.map { it.id }
        if (currentOrder != snapshot.entries.map { it.id } && currentOrder != result.orderedIds) return false
        if (currentOrder == result.orderedIds) return true
        val ordered = result.orderedIds.map { byId.getValue(it) }
        val grid = FolderGridOrganizer(snapshot.columns, snapshot.rows).setContentSize(ordered.size)
        items.clear()
        items.addAll(ordered)
        ordered.forEachIndexed { rank, item ->
            val position = grid.getPosForRank(rank)
            item.rank = rank
            item.cellX = position.x
            item.cellY = position.y
        }
        if (folder.info === snapshot.folderInfo) folder.folderIcon?.onItemsChanged(false)
        // Use the existing writer's notification/repository machinery after updating shared
        // objects on MAIN. Child ranks already committed atomically with their original order.
        val writer = snapshot.launcher.modelWriter
        snapshot.launcher.model.enqueueModelUpdateTask { _, dataModel, _ ->
            // Deleting a folder can race a completed sort. Never ask ModelWriter to update a
            // detached item that is no longer the canonical object in this model generation.
            if (isCurrent(snapshot) && dataModel.itemsIdMap[snapshot.folderId] === snapshot.folderInfo) {
                writer.updateItemInDatabase(snapshot.folderInfo)
            }
        }
        return true
    }

    private data class Snapshot(
        val launcher: LawnchairLauncher,
        val folderInfo: com.android.launcher3.model.data.FolderInfo,
        val folderId: Int,
        val loadId: Int,
        val layout: String,
        val entries: List<FolderSortEntry>,
        val columns: Int,
        val rows: Int,
        val locale: Locale,
    )

    private fun capture(folder: Folder): Snapshot? {
        if (folder.isDestroyed || folder.isInAppDrawer) return null
        val launcher = folder.context.launcherNullable?.takeUnless { it.isDestroyed } ?: return null
        val info = folder.info ?: return null
        if (info.id < 0 || !launcher.model.isModelLoaded()) return null
        val users = UserCache.INSTANCE.get(launcher)
        val entries = info.getContents().map { item ->
            if (item.user !in users.userProfiles) return null
            val serial = users.getSerialNumberForUser(item.user)
            val identity = if (item is AppPairInfo) {
                val children = item.getAppContents().map { child ->
                    if (child.user !in users.userProfiles) return null
                    identity(child, users.getSerialNumberForUser(child.user)) ?: return null
                }.sorted()
                "pair:${children.joinToString(":") { "${it.length}:$it" }}"
            } else {
                identity(item, serial) ?: return null
            }
            FolderSortEntry(
                item.id,
                identity,
                item.title?.toString().orEmpty(),
                if (item is AppPairInfo) null else identity,
            )
        }
        val dp = launcher.deviceProfile
        return Snapshot(
            launcher,
            info,
            info.id,
            launcher.model.lastLoadId,
            if (PreferenceManager2.getInstance(launcher).deckLayout.firstCached()) "deck" else "desktop",
            entries,
            dp.numFolderColumns,
            dp.numFolderRows,
            folder.resources.configuration.locales[0] ?: Locale.getDefault(),
        )
    }

    private fun isCurrent(snapshot: Snapshot): Boolean = !snapshot.launcher.isDestroyed &&
        snapshot.launcher.model.isModelLoaded() && snapshot.launcher.model.lastLoadId == snapshot.loadId

    private fun isLocked(context: Context): Boolean = PreferenceManager2.getInstance(context).lockHomeScreen.firstCached()

    private fun layout(context: Context, db: SQLiteDatabase): String {
        val controller = LauncherAppState.getInstance(context).model.modelDbController
        check(controller.db === db)
        return if (DeckLayoutStore.isDeckActive(context, controller)) "deck" else "desktop"
    }

    private fun identity(item: ItemInfo, serial: Long): String? = identity(item.itemType, item.intent, serial)

    private fun identity(type: Int, intent: Intent?, serial: Long): String? = when (type) {
        Favorites.ITEM_TYPE_APPLICATION -> intent?.component?.flattenToString()?.let { "app:$serial:$it" }

        Favorites.ITEM_TYPE_DEEP_SHORTCUT -> {
            val pkg = intent?.`package`
            val id = intent?.getStringExtra(ShortcutKey.EXTRA_SHORTCUT_ID)
            if (pkg.isNullOrEmpty() || id.isNullOrEmpty()) null else "shortcut:$serial:${pkg.length}:$pkg:$id"
        }

        else -> intent?.let {
            val normalized = Intent(it).apply {
                sourceBounds = null
                flags = 0
            }
            "item:$type:$serial:${normalized.toUri(0)}"
        }
    }

    private fun matchesDatabase(db: SQLiteDatabase, snapshot: Snapshot): Boolean {
        val stored = db.query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites._ID, Favorites.ITEM_TYPE, Favorites.PROFILE_ID, Favorites.INTENT),
            "${Favorites.CONTAINER} = ?",
            arrayOf(snapshot.folderId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            buildMap<Int, String> {
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    val type = cursor.getInt(1)
                    val serial = cursor.getLong(2)
                    val value = if (type == Favorites.ITEM_TYPE_APP_PAIR) {
                        val children = db.query(
                            Favorites.TABLE_NAME,
                            arrayOf(Favorites.ITEM_TYPE, Favorites.PROFILE_ID, Favorites.INTENT),
                            "${Favorites.CONTAINER} = ?",
                            arrayOf(id.toString()),
                            null,
                            null,
                            null,
                        ).use { pair ->
                            buildList {
                                while (pair.moveToNext()) {
                                    val intent = if (pair.isNull(2)) null else Intent.parseUri(pair.getString(2), 0)
                                    add(identity(pair.getInt(0), intent, pair.getLong(1)) ?: return false)
                                }
                            }
                        }.sorted()
                        "pair:${children.joinToString(":") { "${it.length}:$it" }}"
                    } else {
                        identity(type, if (cursor.isNull(3)) null else Intent.parseUri(cursor.getString(3), 0), serial) ?: return false
                    }
                    put(id, value)
                }
            }
        }
        return stored == snapshot.entries.associate { it.id to it.identity }
    }

    private const val TAG = "FolderSortController"
}
