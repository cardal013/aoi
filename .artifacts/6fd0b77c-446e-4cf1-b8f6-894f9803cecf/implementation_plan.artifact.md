# Implementation Plan - Reading Status Refinements

This plan details the corrections to the Reading Status implementation, including reordering tabs, changing the default status, UI cleanup, and restoring the "Continue Reading" button.

## User Review Required

> [!NOTE]
> **Default Status**: The default status for newly favorited mangas is now **Reading** (numerical value 2), as requested.
> **Continue Reading Button**: I investigated the cause of its disappearance. It was primarily due to a check for `unreadCount > 0` in the library grid/list components. This prevented the button from showing on mangas in the "Completed" or "Dropped" tabs (which often have 0 unread chapters). I will remove this check to ensure the button is available in all status tabs, consistent with your request.

## Proposed Changes

### Domain Layer

#### [MODIFY] [ReadingStatus.kt](file:///C:/aoi/domain/src/main/java/tachiyomi/domain/manga/model/ReadingStatus.kt)
- Reorder entries to: `READING(2L), COMPLETED(3L), DROPPED(4L), PLAN_TO_READ(1L)`.
- Numerical values are preserved as requested.
- Update `fromInt` to return `READING` as the default.
- Update `categoryId` mapping to maintain the reserved IDs.

### Data Layer

#### [MODIFY] [mangas.sq](file:///C:/aoi/data/src/main/sqldelight/tachiyomi/data/mangas.sq)
- Update `reading_status` column definition to `DEFAULT 2`.
- Update `reset_reading_status_on_unfavorite` trigger to set `reading_status = 2`.

#### [MODIFY] [15.sqm](file:///C:/aoi/data/src/main/sqldelight/tachiyomi/migrations/15.sqm)
- Update migration to use `DEFAULT 2` and reset to `2` in the trigger.

### Presentation Layer - Manga Details

#### [MODIFY] [MangaInfoHeader.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt)
- Remove the "Smart Update" (Hourglass) `MangaActionButton`.
- Use `MaterialSymbols.RoundedFilled.Bookmark` for the reading status button to show a filled icon.

### Presentation Layer - Library

#### [MODIFY] [LibraryCompactGrid.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt), [LibraryComfortableGrid.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/library/components/LibraryComfortableGrid.kt), [LibraryList.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt)
- Remove the `libraryItem.unreadCount > 0` condition for showing the "Continue Reading" button.
- This allows the button to appear in all status tabs (if the global setting is enabled).

## Verification Plan

### Automated Tests
- Update `ReadingStatusTest` to verify the new order and default value.
- Run `:app:assembleDebug` to ensure compilation.

### Manual Verification
1. **Library Tabs**: Verify order: Reading, Completed, Dropped, Plan to Read.
2. **Manga Details**: Verify "Smart Update" is removed.
3. **Manga Details**: Verify status button icon is a filled bookmark.
4. **Library Covers**: Verify "Play" (Continue Reading) button appears on mangas even if they are in "Completed" or "Dropped" tabs (and have 0 unread chapters).
5. **Default Status**: Add a new manga to favorites and verify it appears in the "Reading" tab by default.
