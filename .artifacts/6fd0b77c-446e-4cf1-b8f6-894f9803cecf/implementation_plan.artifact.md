# Implementation Plan - Sync & UI Refinements

This plan addresses the UI refinements for the Account screen, fixes the Cloud Library navigation bug, and implements the new "Update Account" sync feature.

## User Review Required

> [!IMPORTANT]
> **Sync Logic**: The "Import" and "Upload" features currently perform a **full overwrite** without merging.
> - **Upload**: Clears your Supabase library and replaces it with your current local favorites.
> - **Import (Cloud Restore)**: Clears your local favorites (sets `favorite = false`). For every manga in the cloud, it will either update the local favorite status or **create a new local entry** if it doesn't exist, using the stored source and URL metadata.

## Proposed Changes

### 1. UI Refinements (Profile Screen)

#### [MODIFY] [AccountScreenContent.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/account/AccountScreenContent.kt)
- **Buttons**:
    - Convert "My Cloud Library" and "Continue to app" from `Button` to `TextButton`.
    - Remove background colors.
    - Set text color to white (`MaterialTheme.colorScheme.onSurface`).
    - Keep "Logout" as a red `TextButton`.
    - Add icons to make them more discoverable (e.g., `Cloud`, `ArrowForward`).
- **"Update Account" Button**:
    - Add a new `TextButton` labeled "Update Account".
    - Opens a `SyncOptionsDialog` with two choices: "Import from cloud" and "Upload to cloud".
- **Dialogs**:
    - Implement `SyncOptionsDialog`.
    - Implement `SyncConfirmationDialog` with the warning messages and Cancel/Confirm actions.

### 2. Bug Fix: Cloud Library Navigation

#### [MODIFY] [CloudLibraryScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/CloudLibraryScreen.kt)
- Ensure `PrimaryTabRow` and `HorizontalPager` correctly share the same `pagerState`.
- Verify `pagerState.currentPage` is used for `selectedTabIndex`.
- Ensure `onClick` triggers `animateScrollToPage`.

### 3. Sync Feature (Data & Logic)

#### [MODIFY] [LibrarySupabaseRepository.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/data/account/LibrarySupabaseRepository.kt)
- Add `deleteAllLibrary(userId: String)` using `delete` filter on `user_library`.
- Add `uploadLibrary(userId: String, items: List<Manga>)`:
    - Handles inserting into `manga`, `manga_sources`, and `user_library` tables.
- Add `fetchRemoteLibraryRaw(userId: String)` for import matching.

#### [MODIFY] [AccountViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/AccountViewModel.kt)
- Implement `uploadToCloud()`:
    - Fetch local favorites via `MangaRepository`.
    - Call repository to replace remote library.
- Implement `importFromCloud()`:
    - Fetch remote items.
    - Bulk update local `favorite` status.

---

## Verification Plan

### Manual Verification
1.  **Profile UI**: Verify buttons are now text-only with correct colors and icons.
2.  **Cloud Tabs**: Verify that clicking "Completed" or "Dropped" correctly scrolls the pager and shows the correct mangas.
3.  **Upload to Cloud**:
    - Add a new manga locally.
    - Use "Upload to cloud".
    - Check the Cloud Library screen to see if the manga appears there.
4.  **Import from Cloud**:
    - Clear local library.
    - Use "Import from cloud".
    - Verify that mangas from the cloud are now marked as favorites locally.
