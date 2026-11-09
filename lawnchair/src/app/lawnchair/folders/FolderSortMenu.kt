package app.lawnchair.folders

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.android.launcher3.R
import kotlin.math.roundToInt

/** A separate restore action keeps the saved original order distinct from manual dragging. */
object FolderSortMenu {
    fun show(
        context: Context,
        result: FolderSortResult,
        onSelected: (FolderSortMode) -> Unit,
        onRename: () -> Unit,
        onDismiss: () -> Unit,
    ): AlertDialog {
        val choices = listOf(FolderSortMode.ALPHABETICAL, FolderSortMode.MOST_USED)
        val density = context.resources.displayMetrics.density
        val spacing = (24 * density).roundToInt()
        val targetHeight = (48 * density).roundToInt()
        lateinit var dialog: AlertDialog
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            choices.forEach { mode ->
                addView(
                    RadioButton(context).apply {
                        id = View.generateViewId()
                        setText(if (mode == FolderSortMode.ALPHABETICAL) R.string.folder_sort_alphabetical else R.string.folder_sort_most_used)
                        minHeight = targetHeight
                        isChecked = mode == result.mode
                        setOnClickListener {
                            dialog.dismiss()
                            onSelected(mode)
                        }
                    },
                    RadioGroup.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, 0, spacing, 0)
            addView(group)
            addView(
                TextView(context).apply {
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    setText(R.string.folder_sort_most_used_hint)
                    setPadding(0, (8 * density).roundToInt(), 0, (8 * density).roundToInt())
                },
            )
            addView(
                Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.folder_sort_rename)
                    isAllCaps = false
                    minHeight = targetHeight
                    setOnClickListener {
                        dialog.dismiss()
                        onRename()
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.folder_sort_title)
            .setView(content)
            .setNeutralButton(R.string.folder_sort_restore_original) { _, _ ->
                onSelected(FolderSortMode.MANUAL)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = result.hasOriginal
        }
        dialog.show()
        return dialog
    }
}
