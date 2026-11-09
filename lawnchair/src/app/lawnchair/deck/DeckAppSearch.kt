package app.lawnchair.deck

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.asState
import app.lawnchair.search.algorithms.SearchUtils
import app.lawnchair.views.ComposeBottomSheet
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.pm.UserCache
import com.android.launcher3.touch.ItemClickHandler
import com.google.accompanist.drawablepainter.rememberDrawablePainter

/** Search is an action on the app catalogue, never a second surface for pinning apps. */
object DeckAppSearch {
    fun show(launcher: Launcher) {
        ComposeBottomSheet.show(context = launcher, contentPaddings = PaddingValues(16.dp)) {
            val prefs = remember { PreferenceManager2.getInstance(launcher) }
            val store = launcher.appsView.appsStore
            var apps by remember { mutableStateOf(store.apps.toList()) }
            var query by remember { mutableStateOf("") }
            val hiddenApps by prefs.hiddenApps.asState()
            val hiddenAppsInSearch by prefs.hiddenAppsInSearch.asState()
            val focusRequester = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current

            DisposableEffect(store) {
                val listener = AllAppsStore.OnUpdateListener { apps = store.apps.toList() }
                store.addUpdateListener(listener)
                onDispose { store.removeUpdateListener(listener) }
            }
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboard?.show()
            }

            val matches = remember(apps, query, hiddenApps, hiddenAppsInSearch) {
                if (query.isBlank()) {
                    emptyList()
                } else {
                    val userCache = UserCache.INSTANCE.get(launcher)
                    val users = userCache.userProfiles.toSet()
                    SearchUtils.normalSearch(
                        apps.filter { it.user in users && !userCache.getUserInfo(it.user).isPrivate },
                        query,
                        50,
                        hiddenApps,
                        hiddenAppsInSearch,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.all_apps_search_bar_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                if (query.isNotBlank() && matches.isEmpty()) {
                    Text(stringResource(R.string.all_apps_no_search_results, query))
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(matches, key = { it.toComponentKey().toString() }) { app ->
                        val icon = remember(app, app.bitmap, app.runtimeStatusFlags) { app.newIcon(launcher) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    if (app.isDisabled && ItemClickHandler.handleDisabledItemClicked(app.makeWorkspaceItem(launcher), launcher)) {
                                        return@clickable
                                    }
                                    close(true)
                                    launcher.startActivitySafely(null, app.intent, app)
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Image(
                                painter = rememberDrawablePainter(icon),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(app.title?.toString().orEmpty())
                        }
                    }
                }
            }
        }
    }
}
