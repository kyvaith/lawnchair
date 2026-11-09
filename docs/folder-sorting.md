# Folder sorting

Each native workspace folder has its own sort mode, exposed by the overflow button in its footer:

- Alphabetically: use the current locale's Collator, with stable ties.
- Most used (automatic): highest local launch count first. Update when the folder is opened;
  retain the original relative order for ties and apps without recorded launches.
- Restore original order: restore the order retained before the first sort and return to manual
  mode. Later changes between alphabetical and usage sorting never overwrite that original.

Restore includes only surviving current members. Items added since the original was retained are
appended; removed or moved items are never resurrected. A successful internal manual rearrangement
returns the folder to manual mode while keeping the restore action available. Automatic sorting
is paused while the home screen is locked, during drag-and-drop and while editing the name.

## Local usage counts

`LawnchairLauncher.logAppLaunch` records launches after the normal Android start call succeeds.
This does not prove that the target activity became visible. It covers launches through this
launcher from this version onwards, not all phone usage, notification taps or time spent in apps.
There is no request for system Usage Access and no dependency on the optional prediction service.
Counts use exact launcher component/profile-serial identities. Deep shortcuts have separate
identities. App pairs remain indivisible actions and are not counted as a single app; they keep
their original relative order when their usage count is unavailable. Private/unknown profiles,
uninstalled promise icons and non-pinnable entries are excluded from launch recording.

The application-private `lawnchair-folder-usage.db` keeps counters across restarts, workspace
layout switches and grid changes. Counts saturate at Long.MAX_VALUE rather than overflowing.

## Storage and integration

`FolderSortStore` keeps mode and original membership tokens in the active launcher database,
separately for desktop/Deck and each folder ID. Each token includes a placement ID and stable
item identity, so ordinary duplicate pins stay distinct and reused item IDs cannot take another
app's original position. App-pair members and their encoded split-screen ranks are not flattened
or changed by sorting the containing folder.

Sort mode, the retained original and all native child ranks/coordinates commit in one SQLite
transaction. Inputs are immutable UI snapshots, checked against current persisted membership and
the current model/layout before writing. The canonical FolderInfo is then updated on MAIN even
if the originating folder view was closed. A conflicting model update triggers a native reload
rather than overwriting newly edited contents. Sorting errors leave the transaction rolled back.

`LawnchairFolder` and `LawnchairFolderIcon` are supplied through LawnchairLayoutFactory. Native
folder opening, closing, rename and drag behavior stay in the superclass. Async view updates wait
until the opening animation ends; Back and outside taps keep the native close behavior. A pending
sort blocks interactions inside the folder until its new order is ready. The menu includes a
rename action so narrow folder footers still allow editing the title.

The only added inherited-code hooks clear folder-sort metadata on a full database reset and when
a newly created folder receives an ID. Restoring a saved Deck layout bypasses the new-folder hook,
so the two layout scopes retain their own sort settings and originals. No Launcher3 database
version or core Folder.java changes are required.

Replacing Favorites during a grid migration clears the destination's sorting metadata to avoid
attaching an unrelated folder's original to a reused ID. The native current positions are kept;
automatic migration of saved original-order metadata across such a replacement is not provided.

## Validation

From the repository root, with its existing JDK and Android SDK configured:

```shell
./gradlew -p tools/deck-tests testDebugUnitTest --max-workers=2
./gradlew assembleLawnWithQuickstepGithubDebug spotlessCheck --max-workers=2
```

The isolated suite compiles the original policy, state store and usage store. Its native Android
SQLite tests cover durability, rollback of metadata with native ranks, ID reuse, independent
folders/layouts/grids, profile keys and counter failures/overflow. Policy tests include locale
collation, stable ties, membership changes and randomized permutations. This does not replace
running the full launcher UI on Android.

Device acceptance should include multi-page and two-item folders, long Polish labels, TalkBack,
opening/closing while sorting, immediate Home/Back, interrupted restore, repeated restart, manual
drag, external drop, work-profile copies, app pairs, and switching desktop/Deck with identical
folder IDs. Full-profile installation still uses the existing APK permissions and signing identity.
