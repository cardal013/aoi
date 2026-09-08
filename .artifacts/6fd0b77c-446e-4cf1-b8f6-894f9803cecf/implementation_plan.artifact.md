# Implementation Plan - UI Cleanup & Backup Shortcut Relocation

Relocate the "Import backup" button to the main "More" tab for better visibility and remove the recently added "Grid size" (columns) customization from the Library.

## Proposed Changes

### 1. Relocate "Import backup" Button
- **Source**: Currently in `AccountScreen`.
- **Destination**: Main `MoreTab`, positioned between "Account" and "Download queue".

#### [MODIFY] [AccountScreenContent.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/account/AccountScreenContent.kt)
- Remove `onImportBackup` parameter.
- Remove "Import backup" `ProfileActionButton` from the UI.

#### [MODIFY] [AccountScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/AccountScreen.kt)
- Cleanup: Remove backup file picker logic, `chooseBackup` launcher, and all related imports.
- Remove `onImportBackup` callback passing.

#### [MODIFY] [MoreScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt)
- Add `onClickImportBackup` callback.
- Insert a `TextPreferenceWidget` for "Import backup" (with `Storage` icon) before the `HorizontalDivider` that leads to the Download Queue.

#### [MODIFY] [MoreTab.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt)
- Implement the `chooseBackup` launcher logic (reusing standard Mihon restore flow).
- Add navigation to `RestoreBackupScreen`.

---

### 2. Remove "Grid size" Customization
- Revert all changes related to the custom number of columns per row.

#### [MODIFY] [LibraryToolbar.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/library/components/LibraryToolbar.kt)
- Remove `onClickGridSize` and the corresponding overflow menu item.

#### [MODIFY] [LibraryViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt)
- Remove `showGridSizeDialog`, `setGridSize`, and `Dialog.GridSize`.

#### [MODIFY] [LibraryTab.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt)
- Remove dialog handling for `GridSize`.

#### [MODIFY] [MangaDialogs.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/manga/components/MangaDialogs.kt)
- Delete the `GridSizeDialog` composable.

#### [MODIFY] [CommonMangaItem.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/library/components/CommonMangaItem.kt)
- Revert adaptive button size logic.
- Standardize "Play" button size to `32.dp`.

## Verification Plan

### Manual Verification
- **More Tab**: Verify "Import backup" is visible and functional between "Account" and "Download queue".
- **Library**: Verify the "Grid size" / "Items per row" option is removed from the menu.
- **Library**: Verify the "Play" button size is back to normal.
