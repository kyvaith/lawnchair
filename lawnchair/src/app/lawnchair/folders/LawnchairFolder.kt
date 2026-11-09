package app.lawnchair.folders

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderGridOrganizer.createFolderGridOrganizer
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.folder.FolderNameEditText
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.Themes
import kotlin.math.roundToInt

/** Workspace folder controls built around the existing Launcher3 folder and drag behavior. */
class LawnchairFolder(context: Context, attrs: AttributeSet) : Folder(context, attrs) {
    private lateinit var sortButton: ImageButton
    private var sortDialog: android.app.AlertDialog? = null
    private var requestGeneration = 0
    private var pendingOrder: PendingOrder? = null
    private var folderState = STATE_CLOSED
    private var dragStartFolder: FolderInfo? = null
    private var dragStartOrder: List<Int>? = null

    var isSortingBusy = false
        private set

    override fun onFinishInflate() {
        super.onFinishInflate()
        val buttonSize = (48 * resources.displayMetrics.density).roundToInt()
        val buttonPadding = (12 * resources.displayMetrics.density).roundToInt()
        sortButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            setImageResource(R.drawable.ic_more_vert_dots)
            imageTintList = ColorStateList.valueOf(Themes.getAttrColor(context, R.attr.folderTextColor))
            contentDescription = context.getString(R.string.folder_sort_title)
            tooltipText = contentDescription
            minimumWidth = buttonSize
            minimumHeight = buttonSize
            setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)
            visibility = GONE
            setOnClickListener { inspectAndShowMenu() }
        }
        mFooter.addView(
            sortButton,
            LinearLayout.LayoutParams(buttonSize, buttonSize).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        addOnFolderStateChangedListener { state ->
            folderState = state
            if (state == STATE_OPEN) {
                pendingOrder?.let { pending ->
                    // STATE_OPEN is dispatched inside the native animation's end listener. Wait
                    // one UI turn before replacing views that other end listeners may still use.
                    post {
                        if (pendingOrder !== pending) return@post
                        pendingOrder = null
                        if (pending.snapshot.generation == requestGeneration && isOpen &&
                            folderState == STATE_OPEN && isCurrent(pending.snapshot)
                        ) {
                            applyResult(pending.result, bindViews = true)
                        }
                        setSortingBusy(false)
                    }
                }
            }
            updateSortButton()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSortButton()
    }

    override fun onDetachedFromWindow() {
        requestGeneration++
        pendingOrder = null
        setSortingBusy(false)
        sortDialog?.dismiss()
        sortDialog = null
        super.onDetachedFromWindow()
    }

    override fun animateOpen() {
        if (isSortingBusy || isOpen) return
        // Open synchronously so native Back, outside-touch and folder-switch handling can close
        // the view while its ordering request is pending. An async result never opens a folder.
        super.animateOpen()
        if (!isOpen || !canSort() || isDragging() || isEditingName) return
        val snapshot = captureRequest()
        setSortingBusy(true)
        FolderSortController.request(this) { result ->
            if (snapshot.generation != requestGeneration) return@request
            if (result == null || !isCurrent(snapshot) || !isOpen) {
                setSortingBusy(false)
                return@request
            }
            if (folderState == STATE_OPEN) {
                applyResult(result, bindViews = true)
                setSortingBusy(false)
            } else if (folderState == STATE_ANIMATING) {
                // Preserve the native opening animation's view positions until it completes.
                pendingOrder = PendingOrder(snapshot, result)
            } else {
                setSortingBusy(false)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (isSortingBusy) true else super.dispatchTouchEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (isSortingBusy && event.keyCode != KeyEvent.KEYCODE_BACK &&
        event.keyCode != KeyEvent.KEYCODE_ESCAPE && event.keyCode != KeyEvent.KEYCODE_HOME
    ) {
        true
    } else {
        super.dispatchKeyEvent(event)
    }

    override fun startDrag(view: View, options: DragOptions): Boolean {
        if (isSortingBusy) return false
        dragStartFolder = info
        dragStartOrder = info.getContents().map { it.id }
        return super.startDrag(view, options)
    }

    override fun onDragStart(dragObject: DropTarget.DragObject, options: DragOptions) {
        if (dragObject.dragSource === this && dragStartOrder == null) {
            dragStartFolder = info
            dragStartOrder = info.getContents().map { it.id }
        }
        super.onDragStart(dragObject, options)
        updateSortButton()
    }

    override fun onDropCompleted(target: View?, dragObject: DropTarget.DragObject, success: Boolean) {
        val original = dragStartOrder
        val originalFolder = dragStartFolder
        super.onDropCompleted(target, dragObject, success)
        dragStartOrder = null
        dragStartFolder = null
        val current = info.getContents().map { it.id }
        if (success && target === this && originalFolder === info && original != null &&
            original != current && original.size == current.size && original.toSet() == current.toSet() &&
            canSort()
        ) {
            // A manual rearrangement ends automatic sorting without discarding the saved original.
            FolderSortController.useManualOrder(this)
        }
        updateSortButton()
    }

    override fun onDragEnd() {
        super.onDragEnd()
        dragStartOrder = null
        dragStartFolder = null
        updateSortButton()
    }

    override fun acceptDrop(dragObject: DropTarget.DragObject): Boolean = !isSortingBusy && super.acceptDrop(dragObject)

    override fun isDropEnabled(): Boolean = !isSortingBusy && super.isDropEnabled()

    override fun beginExternalDrag() {
        if (!isSortingBusy) super.beginExternalDrag()
    }

    override fun startEditingFolderName() {
        if (!isSortingBusy) super.startEditingFolderName()
    }

    private fun inspectAndShowMenu() {
        if (!canSort() || isSortingBusy || folderState != STATE_OPEN || isDragging()) return
        if (isEditingName) onBackKey()
        val snapshot = captureRequest()
        setSortingBusy(true)
        FolderSortController.inspect(this) { result ->
            if (snapshot.generation != requestGeneration) return@inspect
            setSortingBusy(false)
            val launcher = mActivityContext as? Launcher ?: return@inspect
            if (result == null || !isCurrent(snapshot) || !canSort() || !isOpen || folderState != STATE_OPEN ||
                !launcher.isResumed || !launcher.isInState(LauncherState.NORMAL) ||
                launcher.window?.decorView?.windowVisibility != VISIBLE
            ) {
                return@inspect
            }
            sortDialog?.dismiss()
            sortDialog = FolderSortMenu.show(
                context,
                result,
                onSelected = ::requestOrder,
                onRename = ::renameFolder,
                onDismiss = { sortDialog = null },
            )
        }
    }

    private fun requestOrder(mode: FolderSortMode) {
        if (!canSort() || isSortingBusy || !isOpen || folderState != STATE_OPEN || isDragging()) return
        val snapshot = captureRequest()
        setSortingBusy(true)
        FolderSortController.request(this, mode) { result ->
            if (snapshot.generation != requestGeneration) return@request
            setSortingBusy(false)
            if (result == null || !isCurrent(snapshot) || !isOpen || folderState != STATE_OPEN) return@request
            if (applyResult(result, bindViews = true)) {
                val launcher = mActivityContext as? Launcher
                if (launcher?.isResumed == true && launcher.window?.decorView?.windowVisibility == VISIBLE) {
                    announceForAccessibility(context.getString(R.string.folder_sort_applied))
                }
            }
        }
    }

    private fun applyResult(result: FolderSortResult, bindViews: Boolean): Boolean {
        val items = info.getContents()
        val byId = items.associateBy { it.id }
        if (byId.size != items.size || result.orderedIds.size != items.size ||
            result.orderedIds.distinct().size != items.size || result.orderedIds.toSet() != byId.keys
        ) {
            return false
        }
        val rebindViews = bindViews && content.areViewsBound() &&
            getIconsInReadingOrder().map { (it.tag as? ItemInfo)?.id } != result.orderedIds
        if (result.orderedIds == items.map { it.id } && !rebindViews) return true
        val ordered = result.orderedIds.map { byId.getValue(it) }
        items.clear()
        items.addAll(ordered)
        val organizer = createFolderGridOrganizer(mActivityContext.deviceProfile).setFolderInfo(info)
        ordered.forEachIndexed { index, item ->
            val position = organizer.getPosForRank(index)
            item.rank = index
            item.cellX = position.x
            item.cellY = position.y
        }
        if (rebindViews) content.bindItems(ordered)
        reapplyItemInfo()
        if (bindViews) updateTextViewFocus()
        folderIcon.onItemsChanged(false)
        mActivityContext.modelWriter.notifyItemModified(info)
        return true
    }

    private fun canSort(): Boolean = mInfo != null && info.id != ItemInfo.NO_ID && !isInAppDrawer &&
        mActivityContext is Launcher && !PreferenceManager2.getInstance(context).lockHomeScreen.firstCached()

    private fun captureRequest(): RequestSnapshot = RequestSnapshot(
        ++requestGeneration,
        info,
        info.getContents().map { it.id }.toSet(),
        LauncherAppState.getInstance(context).model.lastLoadId,
    )

    private fun isCurrent(snapshot: RequestSnapshot): Boolean {
        val launcher = mActivityContext as? Launcher ?: return false
        // A committed order must reach existing views even while the activity is paused. Only
        // menus and announcements require a visible/resumed window; this never opens a folder.
        return !isInAppDrawer && info === snapshot.folder && !isDestroyed &&
            !launcher.isDestroyed && folderIcon.isAttachedToWindow &&
            launcher.model.isModelLoaded() && launcher.model.lastLoadId == snapshot.loadId &&
            info.getContents().map { it.id }.toSet() == snapshot.memberIds &&
            !isDragging()
    }

    private fun isDragging(): Boolean = (mActivityContext as? Launcher)?.dragController?.isDragging == true

    private fun renameFolder() {
        if (!canSort() || isSortingBusy || !isOpen || folderState != STATE_OPEN) return
        // The native footer may hide a short title to make room for page controls. Keep its
        // existing name editor reachable through the menu even in narrow two-icon folders.
        findViewById<FolderNameEditText>(R.id.folder_name).apply {
            visibility = VISIBLE
            requestFocus()
            selectAll()
            showKeyboard()
        }
        startEditingFolderName()
    }

    private fun setSortingBusy(busy: Boolean) {
        isSortingBusy = busy
        updateSortButton()
    }

    private fun updateSortButton() {
        if (!::sortButton.isInitialized) return
        sortButton.visibility = if (mInfo != null && info.id != ItemInfo.NO_ID && !isInAppDrawer && mActivityContext is Launcher) VISIBLE else GONE
        sortButton.isEnabled = !isSortingBusy && folderState == STATE_OPEN && canSort() && !isDragging()
        sortButton.alpha = if (sortButton.isEnabled) 1f else 0.38f
    }

    private data class RequestSnapshot(val generation: Int, val folder: FolderInfo, val memberIds: Set<Int>, val loadId: Int)

    private data class PendingOrder(val snapshot: RequestSnapshot, val result: FolderSortResult)
}

/** A closed folder icon is a separate drop path from the open Folder view. */
class LawnchairFolderIcon(context: Context, attrs: AttributeSet) : FolderIcon(context, attrs) {
    override fun acceptDrop(item: ItemInfo): Boolean = (folder as? LawnchairFolder)?.isSortingBusy != true && super.acceptDrop(item)

    override fun onDragEnter(item: ItemInfo) {
        if ((folder as? LawnchairFolder)?.isSortingBusy != true) super.onDragEnter(item)
    }
}
