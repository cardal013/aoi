# Implementation Plan - Manga Reading Status (Independent from Categories)

This plan implements a "Reading Status" feature for mangas, providing 4 fixed states (Plan to Read, Reading, Completed, Dropped). It includes a dedicated view mode in the Library that organizes mangas by these statuses, keeping the existing category system completely separate and intact.

## User Review Required

> [!IMPORTANT]
> **Database Trigger**: As requested, a database trigger will be added to reset the reading status to `PLAN_TO_READ` (default) whenever a manga is removed from favorites (`favorite = 0`).
> **Library Organization**: A new setting will be added to the Library "Display" settings to toggle between grouping by **Categories** and grouping by **Reading Status**. Choosing "Reading Status" will show the 4 fixed tabs at the top.
> **Virtual Categories Safety**: The 4 status categories will have reserved negative IDs. These are purely visual for the Library Tab and will not be editable or removable in the "Edit Categories" screen.
> **Favorite vs Library**: In this implementation, `favorite == true` is strictly equivalent to "being in the library", following the existing architecture.

## Proposed Changes

### Domain Layer

#### [NEW] [ReadingStatus.kt](file:///C:/aoi/domain/src/main/java/tachiyomi/domain/manga/model/ReadingStatus.kt)
- Define `ReadingStatus` enum: `PLAN_TO_READ(1)`, `READING(2)`, `COMPLETED(3)`, `DROPPED(4)`.

#### [NEW] [LibraryGrouping.kt](file:///C:/aoi/domain/src/main/java/tachiyomi/domain/library/model/LibraryGrouping.kt)
- Define `LibraryGrouping` enum: `BY_CATEGORY`, `BY_STATUS`.

#### [MODIFY] [Manga.kt](file:///C:/aoi/domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt)
- Add `readingStatus: ReadingStatus` to the `Manga` data class.

#### [MODIFY] [MangaUpdate.kt](file:///C:/aoi/domain/src/main/java/tachiyomi/domain/manga/model/MangaUpdate.kt)
- Add `readingStatus: ReadingStatus?` for partial updates.

#### [MODIFY] [LibraryPreferences.kt](file:///C:/aoi/domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt)
- Add `groupLibraryBy` preference (default: `BY_CATEGORY`).

---

### Data Layer

#### [MODIFY] [mangas.sq](file:///C:/aoi/data/src/main/sqldelight/tachiyomi/data/mangas.sq)
- Add `reading_status INTEGER NOT NULL DEFAULT 1` to `mangas` table.
- Update `insertReturningId`, `update`, and `insertNetworkManga` queries.
- Add trigger `reset_reading_status_on_unfavorite` to reset `reading_status` to 1 when `favorite` becomes 0.

#### [NEW] [15.sqm](file:///C:/aoi/data/src/main/sqldelight/tachiyomi/migrations/15.sqm)
- SQL migration to add the column and trigger.

#### [MODIFY] [MangaMapper.kt](file:///C:/aoi/data/src/main/java/tachiyomi/data/manga/MangaMapper.kt)
- Update all mapping functions to handle the new `reading_status` column.

#### [MODIFY] [MangaRepositoryImpl.kt](file:///C:/aoi/data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt)
- Update `partialUpdate` and `insertNetworkManga` to persist the status.

---

### Presentation Layer - Manga Details

#### [MODIFY] [MangaViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt)
- Add `setReadingStatus(status: ReadingStatus)` using `updateManga` interactor.

#### [MODIFY] [MangaInfoHeader.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt)
- Add a new row of "Chips" or a Button in `MangaActionRow` to select the reading status, visible only if `favorite == true`.

---

### Presentation Layer - Library

#### [MODIFY] [LibraryViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt)
- Update `State` to include `groupingMode`.
- In the `library` flow, check `libraryPreferences.groupLibraryBy`.
- If grouping by status:
    - Create 4 "virtual" `Category` objects representing the statuses.
    - Group `favorites` by their `readingStatus`.
    - Ensure all favorites appear in at least one status tab (default is `PLAN_TO_READ`).

#### [MODIFY] [LibrarySettingsDialog.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt)
- Add a "Group by" setting in the Display tab to switch between Categories and Reading Status.

---

### Resources

#### [MODIFY] [strings.xml](file:///C:/aoi/i18n/src/commonMain/moko-resources/base/strings.xml)
- Add strings for: `Plan to Read`, `Reading`, `Completed`, `Dropped`, `Group by`, `Status`.

---

### Verification Plan

#### Automated Tests
- Build the project to ensure `sqldelight` generated code is correct.
- [NEW] Add a unit test to verify that setting `favorite = false` on a manga correctly triggers the `reading_status` reset to `PLAN_TO_READ` (via database trigger simulation or repository verification).

#### Manual Verification
1.  **Manga Screen**: Open a manga, mark as favorite. Change its status. Verify it updates in the DB.
2.  **Manga Screen**: Unmark as favorite. Mark as favorite again. Verify status is reset to "Plan to Read".
3.  **Library Settings**: Go to Library -> Filter -> Display. Change "Group by" to "Status".
4.  **Library Tabs**: Verify 4 tabs (Reading, Plan to Read, Completed, Dropped) appear and filter correctly.
5.  **Library Tabs**: Switch back to "Categories" and verify the old categories are still there, untouched.
