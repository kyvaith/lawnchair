# Native Deck storage

`DeckLayoutStore` stores each home layout as a logical snapshot inside the active launcher
database. The desktop and Deck snapshots contain the complete Favorites rows, including profile
serials, folder contents, widgets and positions. Metadata retains the workspace screen order.
All snapshot, restore and mode-marker changes execute on the model executor in one SQLite
transaction. Restoring uses `ModelDbController.insert`, which validates IDs and advances the
database helper's item-ID counter. No open database, journal or WAL files are copied.

The first entry into Deck retains the current layout. The native model then adds missing apps.
Later switches save the current layout before restoring the other one. User gesture settings and
the normal add-new-apps preference are not changed by switching modes.

SQLite's mode marker is authoritative after the first successful switch. `isDeckActive(context,
controller)` must run on the model executor before reconciling apps. It also repairs the DataStore
mode and any pending screen-order write if the process stopped between the database commit and
the preference update. Screen-order recovery schedules one reload after the current loader, so
an already bound workspace gets the restored order as well. Settings waits for this recovery
before enabling its mode controls.

Restore checks the saved database version, explicit column names, row count and unique item IDs
before changing Favorites. A missing, incomplete or older-schema snapshot aborts the entire
transaction and keeps the active layout. Snapshots from older schemas remain available for a
future explicit migration; they are not silently inserted using an assumed column order. A grid
change that creates a different launcher database does not migrate the inactive layout snapshots.

Ghost-widget cleanup includes IDs owned by saved layouts so switching away does not destroy
their Android widget-host bindings. Layouts may share widget IDs; these bindings must also be
retained when the widget is removed from only one layout. Snapshots contain no duplicate widget
instances or independently copied provider configuration.

A full `DatabaseHelper.createEmptyDB` reset removes both snapshots and the mode marker in the
same transaction as Favorites, so a later mode switch cannot resurrect the discarded layouts.

The pre-bind private-profile filter calls `removePrivateItems` before public apps can use the
vacated cells. This removes private rows from active Deck Favorites as well as from the loaded
model. An existing desktop snapshot is preserved; for legacy Deck installations without one,
the full original layout is snapshotted transactionally before removing any private rows. The
normal desktop layout is not changed by this cleanup.

Legacy `bk_<database>`, `lawndeck_<database>` and corresponding journal files are left untouched.
They are not automatically imported: live file copies made by the old implementation cannot be
assumed to be complete SQLite backups. If an existing old Deck installation has no logical
desktop snapshot yet, switching to desktop retains its current layout instead of overwriting it
from those files. The regular Android restore task is never invoked by mode switching.

## Adding apps across multiple new screens

The native `WorkspaceItemSpaceFinder` asks `DatabaseHelper.getNewScreenId()` for a
screen ID. This reads `MAX(screen)` from persisted desktop Favorites; it does not
reserve an ID in memory. Deck must therefore persist each placement through the
native `ModelWriter` before finding the next position. On the model executor the
writer executes inline. Deferring all writes until after placement planning can
reuse an already full new screen and crash with "Can't find space to add the item".
The final UI callback still binds the newly added items as one batch.

The device regression scenario must include more missing apps than the free cells
on existing screens plus one complete new screen. Confirm multiple distinct new
screen IDs, one primary placement per component/profile, no overlapping cells and
a stable reload without changing the saved desktop snapshot. Pure catalog/identity
planner tests do not exercise the native screen-ID allocator.
