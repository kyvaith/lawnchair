package com.android.launcher3.model

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction

/** Adapter to the controller API; transaction/queries/inserts use Android's real SQLiteDatabase. */
class ModelDbController(val db: SQLiteDatabase) {
    var failInsertId: Long? = null
    var highestInsertedId = 0L

    fun newTransaction() = SQLiteTransaction(db)
    fun delete(selection: String?, args: Array<String>?) = db.delete("favorites", selection, args)
    fun insert(values: ContentValues): Int {
        val id = values.getAsLong("_id")
        if (id == failInsertId) return -1
        highestInsertedId = maxOf(highestInsertedId, id)
        return db.insertOrThrow("favorites", null, values).toInt()
    }
}
