package app.lawnchair.deck

import android.content.Context
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Switches layouts on the model executor, without replacing an open SQLite database. */
class LawndeckManager(context: Context) {
    private val context = context.applicationContext

    suspend fun refreshMode() = withLoadedModel { model ->
        DeckLayoutStore.isDeckActive(context, model.modelDbController)
    }

    suspend fun enableLawndeck() = setEnabled(true)

    suspend fun disableLawndeck() = setEnabled(false)

    private suspend fun setEnabled(enabled: Boolean) = withLoadedModel { model ->
        val controller = model.modelDbController
        val currentMode = DeckLayoutStore.isDeckActive(context, controller)
        DeckLayoutStore(context, controller).switchTo(currentMode, enabled)
        try {
            DeckLayoutStore.isDeckActive(context, controller, reloadOnRecovery = false)
        } finally {
            // The transaction has committed; discard the old in-memory workspace before loading it.
            model.forceReload()
        }
    }

    private suspend fun <T> withLoadedModel(action: (LauncherModel) -> T): T = withContext(NonCancellable) {
        val completion = CompletableDeferred<T>()
        val model = LauncherAppState.getInstance(context).model
        // loadAsync invokes its callback on MODEL_EXECUTOR after any running loader.
        model.loadAsync { dataModel ->
            if (dataModel == null) {
                completion.completeExceptionally(
                    IllegalStateException("The home screen is still loading. Please try again."),
                )
            } else {
                runCatching { action(model) }.fold(
                    onSuccess = { completion.complete(it) },
                    onFailure = { completion.completeExceptionally(it) },
                )
            }
        }
        completion.await()
    }
}
