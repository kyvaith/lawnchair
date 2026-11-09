package com.android.launcher3.util

class IntArray {
    private val values = mutableListOf<Int>()
    fun add(value: Int) {
        values.add(value)
    }
    fun toArray() = values.toIntArray()
}

object Executors {
    val MODEL_EXECUTOR = TestExecutor()
}

class TestExecutor {
    private val pending = ArrayDeque<Runnable>()
    fun post(action: Runnable) {
        pending.addLast(action)
    }
    fun drain() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
    }
    fun clear() = pending.clear()
}
