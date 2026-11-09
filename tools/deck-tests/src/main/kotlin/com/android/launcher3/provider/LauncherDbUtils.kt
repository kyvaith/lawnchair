package com.android.launcher3.provider

import android.database.sqlite.SQLiteDatabase

object LauncherDbUtils {
    fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
            it.moveToFirst()
        }

    class SQLiteTransaction(val db: SQLiteDatabase) : AutoCloseable {
        init {
            db.beginTransaction()
        }
        fun commit() = db.setTransactionSuccessful()
        override fun close() = db.endTransaction()
    }
}
