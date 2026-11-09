package app.lawnchair.deck

import android.content.Context
import android.content.Intent
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.util.SafeCloseable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Keeps the system-integrated taskbar from exposing a second app surface in Deck. */
object DeckTaskbarPolicy {
    @JvmStatic
    fun observe(context: Context, close: Runnable): SafeCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        PreferenceManager2.getInstance(context).deckLayout.get()
            .distinctUntilChanged()
            .onEach { enabled -> if (enabled) close.run() }
            .launchIn(scope)
        return SafeCloseable { scope.cancel() }
    }

    /** Returns true when Deck consumed this request, even if there is no launcher to show. */
    @JvmStatic
    fun redirect(context: Context): Boolean {
        if (!DeckUiPolicy.isEnabled(context)) return false
        val launcher = LawnchairLauncher.instance?.takeUnless { it.isDestroyed } ?: return true
        if (launcher.isResumed && launcher.isInState(LauncherState.NORMAL)) {
            DeckAppSearch.show(launcher)
        } else {
            launcher.startActivity(
                Intent(launcher, LawnchairLauncher::class.java)
                    .setAction(Launcher.INTENT_ACTION_ALL_APPS_TOGGLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        return true
    }
}
