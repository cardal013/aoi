# Implementation Plan - Paginated Progress Fetching

Implement paginated fetching for `user_chapter_progress` to ensure that all remote reading progress is imported, even for very large libraries (exceeding the default PostgREST limit of 1000 rows).

## Problem Analysis

In `reconcileCloudToLocal`, the current code fetches remote progress using a single `.select()` call. By default, Supabase/PostgREST limits results to 1000 rows. With the user's library containing over 5500 rows, only a small portion of the progress is being imported, leading to missing data.

## Proposed Changes

### [Account] [LibrarySupabaseRepository.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/data/account/LibrarySupabaseRepository.kt)

#### 1. Paginated Fetching Logic
- **[MODIFY]** Update the progress fetching block (Step 5) to use a loop with `.range()`.
- **Strategy**:
    1. Fetch chunks of 1000 rows at a time.
    2. Continue fetching until the returned list is smaller than the chunk size (indicating the last page).
    3. Accumulate all results into the final `progressList`.
- **Code Structure**:
    ```kotlin
    val allProgress = mutableListOf<ChapterProgressRemote>()
    var offset = 0
    val pageSize = 1000
    while (true) {
        val chunk = supabase.postgrest["user_chapter_progress"]
            .select {
                filter { eq("user_id", userId) }
                range(offset.toLong(), (offset + pageSize - 1).toLong())
            }
            .decodeList<ChapterProgressRemote>()
        allProgress.addAll(chunk)
        if (chunk.size < pageSize) break
        offset += pageSize
    }
    ```

## Verification Plan

### Manual Verification
1. **Large Library Import**:
   - Use an account with > 1000 reading progress entries.
   - Click **Import from cloud**.
   - Verify in Logcat that multiple pages are being fetched (optional: add logging for pages).
   - Confirm that all mangas in the library have their progress restored, not just the first 1000.
2. **Import Success**:
   - Check the diagnostic log: `progressListTotal` should now show the correct total (e.g., 5599) instead of exactly 1000.
