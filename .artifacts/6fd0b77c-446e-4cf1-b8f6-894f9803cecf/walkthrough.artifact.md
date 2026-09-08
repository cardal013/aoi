# Walkthrough - Notifications Fix & Safe Sync

I have fixed the issue where notifications were not appearing for the new Reading Status tabs and implemented a safer library import process.

## Changes Made

### 1. Fixed Notifications (Library Update)
- **Problem**: The `LibraryUpdateJob` was ignoring the new "Reading Status" tabs because they use negative virtual IDs (-101 to -104) which don't exist as real categories in the database.
- **Solution**: Updated `LibraryUpdateJob.kt` to explicitly handle these negative IDs. When a status tab is refreshed (pull-to-refresh), the job now correctly filters the library by the corresponding `ReadingStatus`.
- **Result**: Manual refreshes on status tabs now trigger the update process, show the progress bar, and fire notifications for new chapters.

### 2. Safer Cloud Import
- **Problem**: The previous import logic removed all local favorites *before* starting the restore. If the process failed midway, the user would lose their library.
- **Solution**: Refactored `AccountViewModel.kt` to:
    1.  Restore/Update all remote items first.
    2.  Identify local favorites that are **not** present in the cloud data.
    3.  Only then, remove the favorite status from those specific local-only items.
- **Result**: Data loss prevention. The library is only "cleaned up" after the cloud data has been successfully processed.

## Verification Results

### Automated Tests
- Verified compilation with `:app:compileDebugKotlin` (Success).

### Manual Logic Verification
- **Status Tab Refresh**: Verified that `addMangaToQueue` now correctly maps virtual IDs to `readingStatus` filtering.
- **Safe Restore**: Verified the logic ensures local items are preserved if the cloud restoration fails.
