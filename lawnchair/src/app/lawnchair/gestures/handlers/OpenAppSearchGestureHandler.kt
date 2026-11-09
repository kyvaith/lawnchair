package app.lawnchair.gestures.handlers

import android.content.Context
import app.lawnchair.LawnchairLauncher
import app.lawnchair.animateToAllApps
import app.lawnchair.deck.DeckAppSearch
import app.lawnchair.deck.DeckUiPolicy

class OpenAppSearchGestureHandler(context: Context) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        if (DeckUiPolicy.isEnabled(launcher)) {
            DeckAppSearch.show(launcher)
            return
        }
        val searchUiManager = launcher.appsView.searchUiManager
        searchUiManager.setDirectFocus(true)
        searchUiManager.editText?.showKeyboard()
        launcher.animateToAllApps()
    }
}
