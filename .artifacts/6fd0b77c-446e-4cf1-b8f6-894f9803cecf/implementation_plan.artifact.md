# Implementation Plan - Sync Stability and Progress UI

Address the Foreign Key violation in the synchronization flow and add a comprehensive progress UI to the Account screen.

## User Review Required

> [!IMPORTANT]
> **Sequential Execution**: To fix the `23503` (FK violation) error, I will change the reconciliation logic to process each manga sequentially: first metadata (`uploadMangaSync`), then chapters (`updateChaptersProgress`). This ensures the parent record in `manga_sources` always exists before chapters reference it.

## Proposed Changes

### [Account] [LibrarySupabaseRepository.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/data/account/LibrarySupabaseRepository.kt)

#### 1. Update Sync Methods for Reporting
- **[MODIFY]** Update `reconcileLocalToCloud`, `reconcileCloudToLocal`, and `backfillAllProgress` to accept callbacks:
    - `onProgress: (current: Int, total: Int) -> Unit`
    - `onMangaFailed: (mangaTitle: String) -> Unit`

#### 2. Fix Sequential Logic in `reconcileLocalToCloud`
- **[MODIFY]** Change the logic to process mangas one by one:
    ```kotlin
    localMangaList.forEachIndexed { index, manga ->
        onProgress(index + 1, localMangaList.size)
        try {
            uploadMangaSync(userId, manga)
            // Immediately sync chapters for THIS manga after metadata success
            val chapters = getChapters.await(manga.id)
            updateChaptersProgress(manga, chapters)
        } catch (e: Exception) {
            onMangaFailed(manga.title)
        }
    }
    ```

### [Account] [AccountViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/AccountViewModel.kt)

#### 1. Expand State
- **[MODIFY]** Add fields to `State`:
    - `syncStatus: SyncStatus` (Idle, Syncing, Success, Error)
    - `syncProgress: Pair<Int, Int>?`
    - `failedMangas: List<String>`

#### 2. Update Sync Actions
- **[MODIFY]** Update `uploadToCloud` and `importFromCloud` to update the state as progress is reported by the repository.

### [Account] [Account UI]

#### 1. [AccountScreenContent.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/account/AccountScreenContent.kt)
- **[NEW]** Add a `SyncProgressOverlay` that shows:
    - A Linear Progress Indicator.
    - A text label: "Syncing: 12/48 mangas".
    - A persistent warning: "Syncing with cloud — please do not leave this screen until finished."
- **[MODIFY]** Disable "Update Account" and "Logout" buttons while `syncStatus == Syncing`.
- **[NEW]** Add a "Sync Failed" section (or dialog) if `failedMangas` is not empty.

## Verification Plan

### Automated Tests
- Build verification: `gradlew :app:assembleDebug`.

### Manual Verification
1. **FK Violation Test**:
    - Re-add the problematic manga with a different source.
    - Click **Update Account**.
    - Verify in Logcat that no `23503` error occurs.
2. **UI Test**:
    - Trigger a sync.
    - Verify that the progress bar appears and updates correctly.
    - Verify that buttons are disabled during sync.
    - Verify the persistent warning is visible.
3. **Failure Summary**:
    - Force a failure (e.g., toggle network) for some mangas.
    - Verify that a summary of failed mangas appears after the operation completes.
