package app.lawnchair.preferences2

import android.content.Context

class TestPreference(var value: Boolean = false)

class PreferenceManager2 {
    val deckLayout = TestPreference()

    companion object {
        val instance = PreferenceManager2()
        fun getInstance(context: Context) = instance
    }
}
