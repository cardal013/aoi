# Walkthrough - Supabase Sync Fixes (FK & Types)

I have resolved several critical issues in the Supabase synchronization logic to ensure data integrity and prevent foreign key violations.

## Changes Made

### 1. Fix: Foreign Key Violation in `manga_sources`
- **Problem**: The `uploadMangaSync` process was failing because it tried to insert into `manga_sources` using an `extension_id` that didn't exist in the `extensions` table.
- **Solution**: Updated `LibrarySupabaseRepository.kt` to perform an **upsert into the `extensions` table** before any source-related operations. This ensures that the extension metadata (ID and Name) is always present.
- **Deterministic IDs**: The `extension_id` is derived directly from Tachiyomi's source ID, ensuring it remains consistent across syncs.

### 2. Fix: UUID Type Mismatch
- **Problem**: Attempting to use strings like `"manga_123"` in a UUID column in PostgreSQL caused immediate failure.
- **Solution**: Switched to using **deterministic UUIDs** generated via `UUID.nameUUIDFromBytes()`. These are valid UUIDs based on the manga's source and URL, ensuring they are unique but repeatable.

### 3. Fix: `added_at` Format
- **Problem**: Passing the string `"now()"` inside a JSON payload to PostgREST doesn't trigger the SQL function; it's treated as a malformed timestamp.
- **Solution**: Now sending a standard ISO-8601 timestamp generated on the client using `java.time.Instant.now()`.

## Verification Results

### Automated Tests
- Verified successful compilation with `:app:compileDebugKotlin`.

### Manual Logic Verification
- **Order of Operations**: The sync now follows: `Extension -> Manga -> Manga Source -> User Library`, respecting all database constraints.
- **Data Consistency**: Using deterministic UUIDs prevents duplicate entries in the remote `manga` and `manga_sources` tables.
