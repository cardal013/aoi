# Implementation Plan - Fix Reconciliation for Empty Favorites

Fix the issue where `reconcileLocalToCloud` fails to clear the cloud library when the local favorites list is empty.

## Problem Analysis

The current code has an explicit guard `if (currentRemoteIds.isNotEmpty())` before the deletion logic. When a user removes all favorites locally, `currentRemoteIds` becomes empty, the guard is triggered, and the deletion logic is skipped entirely. This leaves the old data in the cloud.

Additionally, we need to ensure that the `NOT IN` logic behaves correctly by handling the empty list case as a "delete all for user" operation.

## Proposed Changes

### [Account] [LibrarySupabaseRepository.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/data/account/LibrarySupabaseRepository.kt)

#### 1. Fix `reconcileLocalToCloud` Logic
- **[MODIFY]** Remove the `if (currentRemoteIds.isNotEmpty())` check.
- **[MODIFY]** Update the `filter` block to always apply `eq("user_id", userId)` and conditionally apply `notIn("manga_id", currentRemoteIds)` only if the list is not empty.
- **Logic**:
    ```kotlin
    supabase.postgrest["user_library"].delete {
        filter {
            eq("user_id", userId)
            if (currentRemoteIds.isNotEmpty()) {
                notIn("manga_id", currentRemoteIds)
            }
        }
    }
    ```
- This ensures that if the list is empty, only the `user_id` filter is applied, resulting in a full wipe for that user.

## Verification Plan

### Manual Verification
1. **Clear Favorites**:
   - Remove all manga from favorites locally.
   - Click **Update Account**.
   - Verify in Supabase Dashboard that both `user_library` and `user_chapter_progress` for that user are now completely empty.
2. **Partial Update**:
   - Have 5 favorites locally.
   - Remove 2.
   - Click **Update Account**.
   - Verify that exactly those 2 are removed from the cloud, and the other 3 remain.
