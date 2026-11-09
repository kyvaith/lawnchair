package com.android.launcher3.pm

import android.content.Context
import android.os.UserHandle

class UserCache {
    val serials = linkedMapOf<UserHandle, Long>()
    val userProfiles: List<UserHandle> get() = serials.keys.toList()
    fun getSerialNumberForUser(user: UserHandle) = serials[user] ?: 0L

    companion object {
        val INSTANCE = Provider()
        val instance = UserCache()
    }

    class Provider {
        fun get(context: Context) = instance
    }
}
