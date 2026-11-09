# Native Deck: model, integration and acceptance

Base: `16-dev`, commit `2d072998677ff198a0abc3f19c22af9ad32b5574`.
Reference inspected: Lawndesk `fb10e53effbe87f1295fa4f91f30d9a8b4bd8e76`.

## Architecture decision

Lawndesk also stores native `ITEM_TYPE_APPLICATION` entries in Launcher Favorites. Its distinctive
behavior is workspace membership, not an absence of database entries. Replacing Favorites or
rendering AppInfo objects as an unrelated home screen would require replacing Launcher3's drag,
folders, widget and placement machinery. This implementation retains that machinery.

Deck owns a single primary placement per launchable `(ComponentName, UserHandle)`. The existing
placement in a desktop page, folder or hotseat wins. App pairs are multi-app actions and are not
primary placements. Deep shortcuts remain separate actions. The normal layout retains its usual
optional pins and drawer.

`DeckModel` reads the model's `AllAppsList`, populated by Android LauncherApps callbacks and the
loader. It never reads the drawer view to populate Home, queues optional icon-install requests,
selects only a package's first activity, or infers application identity from a package name alone.
It runs after the complete loader, after model updates and on resume validation. Missing entries
are placed with the existing space finder and ModelWriter. A temporary absence from the catalog
does not delete an existing placement; normal Android package-removal handling remains in charge.
Duplicate repair reloads the workspace to rebuild folder contents even without an active UI.

Personal and managed profiles are supported by the model. New additions require a known,
unlocked, non-quiet profile. Existing unavailable work apps retain their placements and Launcher3
disabled-state behavior. Private Space is excluded from Deck and its search. Existing private
placements are removed from the active Deck database before binding, after retaining the desktop
snapshot, so hidden rows cannot collide with new visible icons. This is not a Private Space UI.
The hidden-app preference prevents automatic addition; an already manually placed home icon is
not automatically deleted by changing the drawer's hidden-app preference.

## Integration boundary

New behavior lives in Kotlin under `lawnchair/src/app/lawnchair/deck`. No Lawndesk source was
copied. The Lawnchair model delegate schedules post-loader work and filters private data before
workspace binding. The mode preference has no reload callback: the transactional manager owns
reload timing, including recovery after interruption.

Small `LC-Note` hooks in the inherited code provide:

- A post-task model reconciliation call, only while the model remains loaded.
- A default-permissive `StatefulContainer.canGoToState` hook and state-manager guards. The
  Lawnchair activity disables ALL_APPS only in Deck; other containers retain the default behavior.
- Consistent touch, popup, accessibility and taskbar policies. QSB/keyboard app search uses a local
  search sheet with no app pinning or drag source. Taskbar has an independent All Apps window and
  therefore needs its own guard.
- Ownership retention for widget host bindings and Android deep-shortcut pins belonging to an
  inactive layout. Saved deep shortcuts are matched by profile serial, package and shortcut ID.
- Snapshot cleanup when the launcher database is explicitly reset.

The old per-package Deck hook in PackageUpdatedTask and the unused folder-copy task are removed.
There are no changes to the Launcher3 database version, Android launch protocol, Quickstep Binder
protocol, build dependency versions, application ID or standard layout's drawer implementation.

Storage and compatibility limits are described in [deck-native.md](deck-native.md).

## Repeatable validation

Use JDK 21, the SDK required by this checkout and its pinned Gradle wrapper. From the repository root:

```shell
./gradlew assembleLawnWithQuickstepGithubDebug spotlessCheck
./gradlew -p tools/deck-tests testDebugUnitTest
git diff --check
```

The isolated test module compiles the actual production planner, snapshot store and saved-shortcut
reader with small adapters for launcher services. SQLite runs in Robolectric NATIVE mode; see its
README for the exact evidence boundary. It does not execute the full launcher, real DataStore,
Android widget providers or gesture controllers.

Before shipping or merging, run on Android (phone and tablet/taskbar):

1. Save the standard layout with a folder, dock icons, widget and pinned-only deep shortcut. Enable
   Deck, restart the process and switch back repeatedly. Check all positions and both bindings.
2. Install/update/remove apps while Home is active, in the background and while the launcher is
   stopped. Include a package with two launcher activities and the same app in a managed profile.
3. Create, pause, resume, lock, unlock and remove a work profile. Check retained disabled icons and
   that new eligible apps appear after availability returns. Check Private Space never appears in
   Deck or Deck search.
4. Rearrange icons, create folders, move to/from the dock and drag between pages. Try all Remove
   actions and TalkBack. Apps must remain represented; widgets and deep shortcuts remain removable.
5. Try swipe, saved gestures, QSB app search, keyboard, overview and system All Apps intents. Repeat
   with taskbar and after rotation/configuration recreation. No drawer or duplicate-pin surface
   should open in Deck. Check the same paths in the normal layout.
6. Interrupt a mode switch after its SQLite commit, restart, and verify mode/screen-order recovery.
   Import/reset a layout and confirm discarded snapshots cannot reappear.

## Updating from Lawnchair / Launcher3

Follow Lawnchair's Kotlin/package and minimal-AOSP-change conventions. Keep this feature on its
own branch and review the marked hooks during upstream merges. In particular recheck model-task
execution, loader completion timing, state animation entry points, folder binding, taskbar overlay
lifecycle, widget cleanup and shortcut pin reconciliation. `LooperExecutor.execute` runs inline
on its own thread; use `post` when work must wait until the current loader has finished.

An upstream database-schema change needs an explicit migration for inactive snapshots. A grid
change to a different database currently does not migrate the inactive snapshot. Restore refuses
incompatible schema versions rather than inserting assumed columns. Do not relax those checks
without migration tests and device round-trip acceptance.

Sources:

- https://docs.lawnchair.app/developers/architecture/project-overview
- https://docs.lawnchair.app/developers/standards/coding-standards
- https://docs.lawnchair.app/developers/standards/development-workflow
- https://github.com/LawnchairLauncher/lawnchair/issues/7138
- https://github.com/j67678/Lawndesk
