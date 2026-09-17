# Implementation Plan - UI Refinements & Auto-Close Sync

Rename library categories for consistency and ensure the synchronization progress overlay closes automatically upon completion.

## Proposed Changes

### 1. Rename Categories & Fix Order
- **[MODIFY] [index.html](file:///C:/aoi/index.html)**:
    - Update `STATUS_LABEL` to use "Plan to Read" instead of "Planned".
    - Reorder `STATUSES` array and the library tab buttons to: **Reading, Completed, Dropped, Plan to Read**.
- **[MODIFY] [ReadingStatus.kt](file:///C:/aoi/domain/src/main/java/tachiyomi/domain/manga/model/ReadingStatus.kt)**:
    - (Verified) The enum order already matches: `READING, COMPLETED, DROPPED, PLAN_TO_READ`.
- **[MODIFY] [base/strings.xml](file:///C:/aoi/i18n/src/commonMain/moko-resources/base/strings.xml)**:
    - (Verified) `reading_status_plan_to_read` already set to "Plan to Read".

### 2. Fix Sync Overlay (Auto-close & Better Progress)
- **[MODIFY] [LibrarySupabaseRepository.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/data/account/LibrarySupabaseRepository.kt)**:
    - Update `reconcileLocalToCloud` and `reconcileCloudToLocal` to report progress throughout the *entire* operation.
    - Currently, the overlay often "hangs" at 100% because the final chapter backfill phase doesn't report progress.
    - Normalize the progress counter so 100% actually means the operation is finished.
- **[MODIFY] [AccountViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/AccountViewModel.kt)**:
    - After a successful sync, wait for a brief moment (e.g., 1s) to allow the user to see the 100% completion state, then transition `syncStatus` back to `Idle`.
    - This will automatically hide the `SyncProgressOverlay` in `AccountScreenContent.kt`.

### 3. Cleanup UI Code
- **[MODIFY] [AccountScreenContent.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/account/AccountScreenContent.kt)**:
    - Ensure the layout is clean and the overlay correctly blocks interactions only during the `Syncing` state.

## Verification Plan

### Manual Verification
1. **Category Names (Site)**:
    - Open the website.
    - Verify tabs are: Reading, Completed, Dropped, Plan to Read.
    - Change a manga status and verify the label "Plan to Read" appears correctly.
2. **Auto-Close Overlay (App)**:
    - Open the Account screen.
    - Click **Update Account**.
    - Watch the progress bar go from 0% to 100%.
    - Verify that the overlay **disappears automatically** once it reaches 100% (with a short delay).
3. **Failure State**:
    - Ensure that if a sync fails, the error dialog *still shows* and doesn't auto-close (manual dismissal required for errors).
