package com.android.launcher3

import android.content.Context
import com.android.launcher3.model.ModelDbController

class LauncherPrefs {
    var screenOrder = ""
    fun get(key: String) = screenOrder
    fun putSync(vararg values: Pair<String, String>) {
        values.forEach { screenOrder = it.second }
    }

    companion object {
        const val WORKSPACE_SCREEN_ORDER = "workspace_screen_order"
        val instance = LauncherPrefs()
        fun get(context: Context) = instance
    }
}

class LauncherAppState {
    val model = TestModel()

    class TestModel {
        lateinit var modelDbController: ModelDbController
        var reloads = 0
        fun forceReload() {
            reloads++
        }
    }

    companion object {
        val instance = LauncherAppState()
        fun getInstance(context: Context) = instance
    }
}

class LauncherSettings {
    object Favorites {
        const val TABLE_NAME = "favorites"
        const val _ID = "_id"
        const val APPWIDGET_ID = "appWidgetId"
        const val ITEM_TYPE = "itemType"
        const val ITEM_TYPE_APPWIDGET = 4
        const val ITEM_TYPE_DEEP_SHORTCUT = 6
        const val PROFILE_ID = "profileId"
        const val INTENT = "intent"
    }
}
