package com.android.launcher3.shortcuts

import android.content.Intent
import android.os.UserHandle

/** API adapter retaining the same package/id/profile identity as the launcher ShortcutKey. */
data class ShortcutKey(val packageName: String, val user: UserHandle, val id: String) {
    companion object {
        const val EXTRA_SHORTCUT_ID = "shortcut_id"
        fun fromIntent(intent: Intent, user: UserHandle) =
            ShortcutKey(requireNotNull(intent.`package`), user, requireNotNull(intent.getStringExtra(EXTRA_SHORTCUT_ID)))
    }
}
