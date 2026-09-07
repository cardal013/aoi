# Walkthrough - Manga Reading Status

I have implemented the "Reading Status" feature, allowing users to track their progress through 4 distinct states: Plan to Read, Reading, Completed, and Dropped. This system works independently of the existing category system.

## Changes Made

### Domain & Data Layer
- **New Enum**: Created `ReadingStatus` with values for the 4 states and associated virtual category IDs.
- **New Preference**: Added `groupLibraryBy` to `LibraryPreferences` to toggle between Category and Status grouping.
- **Database Migration**: Added `reading_status` column to the `mangas` table (defaulting to `PLAN_TO_READ`).
- **Trigger**: Implemented a database trigger that resets the reading status to `PLAN_TO_READ` when a manga is removed from favorites.
- **Repository & Restorer**: Updated `MangaRepository`, `MangaMapper`, and `MangaRestorer` to handle the new field, ensuring compatibility with database operations and backup restores.

### UI Layer - Manga Details
- **Improved Status Button**:
    - Changed the icon to `Bookmark` (as requested, resembling the "saved" symbol).
    - Changed the label to show the **actual status name** (e.g., "Reading", "Plan to Read") instead of the generic "Reading status".
    - Visible only for library items.

### UI Layer - Library
- **Default Organization**: Set "Group by Status" as the default organization mode.
- **Fixed Tabs**: The library now defaults to showing 4 fixed tabs: **Reading, Plan to Read, Completed, Dropped**. These filter mangas based on their reading status.
- **Virtual Categories**: Improved the logic to map these statuses to virtual categories with reserved IDs for seamless navigation, keeping them separate from real categories.

### Resources
- Added localized strings for all statuses and new settings labels.

## Verification Results

### Automated Tests
- Added `ReadingStatusTest` to verify the enum logic and category ID mapping.
- Verified that the SQLDelight generated code compiles correctly after the schema change.

### Manual Verification (Logic Check)
- **Status Reset**: The DB trigger ensures that if a user removes a manga from the library, its status is cleared, fulfilling the business rule.
- **Independence**: Categories remain untouched and fully functional. Users can use both systems in parallel.
- **Safety**: Virtual categories used for status tabs are not displayed in the "Edit Categories" screen because they aren't persisted in the `categories` table.

render_diffs(file:///C:/aoi/domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt)
render_diffs(file:///C:/aoi/data/src/main/sqldelight/tachiyomi/data/mangas.sq)
render_diffs(file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt)
