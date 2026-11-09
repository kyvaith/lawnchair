package com.patrykmichalik.opto.core

import app.lawnchair.preferences2.TestPreference

fun TestPreference.firstBlocking() = value
fun TestPreference.setBlocking(enabled: Boolean) {
    value = enabled
}
