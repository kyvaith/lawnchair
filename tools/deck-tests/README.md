# Deck storage and model regression tests

Run from the repository root with JDK 21 and the Android SDK configured:

```shell
./gradlew -p tools/deck-tests testDebugUnitTest
```

This independent module compiles the original `DeckLayoutStore.kt`, `DeckReconciler.kt` and `DeckShortcutPins.kt` files,
copied to a generated build source directory. No production algorithm is duplicated. The original
standalone reconciler regression suite (including 5,000 seeded randomized cases) runs through JUnit.

Storage tests run Android `SQLiteDatabase` in Robolectric 4.16.1's native SQLite mode on API 34.
They use small API adapters for launcher preferences, model reloads and the database controller.
Transactions, queries, typed values, constraints and rollback execute in native SQLite, not a fake
in-memory database implementation. The controller adapter supports a failed insert to verify that
partially restored Favorites and snapshot/mode changes all roll back.

Coverage includes layout round trips, folders, profile serials, icon blobs, widget bindings,
screen-order recovery, stale preference recovery, malformed snapshots, schema mismatch and reset.
Pinned-shortcut tests verify ownership across both snapshots, deduplication, user serials, malformed
intents, unknown profiles and release of ownership after reset.
The module also compiles the original `FolderSortPolicy.kt`, `FolderSortStore.kt` and
`FolderUsageStore.kt`. Folder tests cover locale collation, stable ties, duplicate app placements,
original-order persistence, manual drags, membership changes, grid/layout/folder isolation,
reused folder IDs and transactional rollback of metadata together with ranks. Launch-counter tests
use the real SQLiteOpenHelper file, including process-style close/reopen, profile and component
identity separation, Long.MAX_VALUE saturation, failed-update rollback and large lookup batches.
The pure folder policy also runs 3,000 seeded membership/idempotence cases for all three modes.
It does not replace full launcher compilation, device UI testing, widget-provider lifecycle tests,
or integration testing of the real DataStore and launcher model/controller.

The module is intentionally separate from the application build to keep its existing test and
dependency configuration unchanged. It needs internet access on its first run to download
Robolectric and its Android runtime; subsequent runs use the standard Gradle/Maven caches.
