package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.manga.model.ReadingStatus as DomainReadingStatus

@Inject
@SingleIn(AppScope::class)
class LibrarySupabaseRepository(
    private val mangaRepository: MangaRepository,
    private val sourceManager: SourceManager,
) {

    private val BATCH_SIZE = 25
    private val sessionMutex = Mutex()

    private suspend fun ensureValidSession() {
        val currentSession = supabase.auth.currentSessionOrNull() ?: return
        val nowMillis = System.currentTimeMillis()
        val expiresAtMillis = currentSession.expiresAt.toEpochMilliseconds()

        // Refresh if expiring in less than 2 minutes (120,000 ms)
        if (expiresAtMillis - nowMillis < 120000) {
            sessionMutex.withLock {
                // Re-check inside lock
                val refreshedSession = supabase.auth.currentSessionOrNull() ?: return@withLock
                if (refreshedSession.expiresAt.toEpochMilliseconds() - System.currentTimeMillis() >= 120000) {
                    return@withLock // Already refreshed by someone else
                }

                logcat(LogPriority.DEBUG) { "AOI_SYNC: Session expiring soon, refreshing..." }
                try {
                    supabase.auth.refreshCurrentSession()
                    logcat(LogPriority.DEBUG) { "AOI_SYNC: Session refreshed successfully" }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "AOI_SYNC: Failed to refresh session" }
                }
            }
        }
    }

    suspend fun getUserLibrary(userId: String): Result<List<UserLibraryItem>> {
        return try {
            // Nested query: fetches user_library entries and joins related tables in one go.
            val columns = Columns.raw("""
                *,
                manga(*),
                manga_sources(*, extensions(*)),
                chapters(*)
            """.trimIndent())

            val results = supabase.postgrest["user_library"]
                .select(columns = columns) {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<UserLibraryEntryRemote>()

            val items = results.map { entry ->
                UserLibraryItem(
                    mangaId = entry.mangaId,
                    title = entry.manga.title,
                    thumbnailUrl = entry.manga.thumbnailUrl,
                    status = entry.status,
                    isFavorite = entry.isFavorite,
                    extensionName = entry.source.extension.name,
                    sourceId = entry.source.extensionId.toLongOrNull() ?: -1L,
                    mangaUrl = entry.source.sourceMangaId,
                    lastChapterNumber = entry.lastChapter?.chapterNumber,
                    lastChapterLabel = entry.lastChapter?.chapterLabel,
                    lastPage = entry.lastPage,
                    lastReadAt = entry.lastReadAt
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Supabase: Error fetching user library" }
            Result.failure(e)
        }
    }

    /**
     * Groups the library items by their reading status for UI tabs.
     */
    fun groupByStatus(list: List<UserLibraryItem>): Map<String, List<UserLibraryItem>> {
        return list.groupBy { it.status }
    }

    suspend fun deleteAllLibrary(userId: String) {
        supabase.postgrest["user_library"].delete {
            filter { eq("user_id", userId) }
        }
    }

    suspend fun uploadMangaSync(userId: String, manga: Manga) {
        // AOI: Proactive session refresh
        ensureValidSession()

        // Generate deterministic UUIDs based on source and url to avoid duplicates
        val remoteMangaId = UUID.nameUUIDFromBytes("manga:${manga.source}:${manga.url}".toByteArray()).toString()
        val remoteSourceId = UUID.nameUUIDFromBytes("source:${manga.source}:${manga.url}".toByteArray()).toString()
        val extensionId = manga.source.toString() // In Tachiyomi, source ID is the extension ID

        var retryCount = 0
        var success = false
        val maxRetries = 2

        while (!success && retryCount <= maxRetries) {
            try {
                // 0. Ensure extension exists in 'extensions' table (required for FK in manga_sources)
                val source = sourceManager.getOrStub(manga.source)
                supabase.postgrest["extensions"].upsert(
                    ExtensionRemote(
                        id = extensionId,
                        name = source.name,
                        isActive = true
                    )
                )

                // 1. Upsert into 'manga' table
                supabase.postgrest["manga"].upsert(
                    MangaRemote(
                        id = remoteMangaId,
                        title = manga.title,
                        author = manga.author,
                        artist = manga.artist,
                        thumbnailUrl = manga.thumbnailUrl
                    )
                )

                // 2. Upsert into 'manga_sources' table
                supabase.postgrest["manga_sources"].upsert(
                    MangaSourceSyncRemote(
                        id = remoteSourceId,
                        mangaId = remoteMangaId,
                        extensionId = extensionId,
                        sourceMangaId = manga.url // The URL is the unique ID within the source
                    )
                )

                // 3. Upsert into 'user_library' table
                supabase.postgrest["user_library"].upsert(
                    UserLibraryEntrySimple(
                        userId = userId,
                        mangaId = remoteMangaId,
                        status = mapToRemoteStatus(manga.readingStatus),
                        isFavorite = manga.favorite,
                        sourceId = remoteSourceId,
                        addedAt = java.time.Instant.now().toString()
                    )
                )
                success = true
            } catch (e: Exception) {
                val isNetworkError = e is java.io.IOException || e.message?.contains("Unable to resolve host") == true || e.message?.contains("timeout") == true

                if (isNetworkError && retryCount < maxRetries) {
                    retryCount++
                    logcat(LogPriority.WARN) { "AOI_SYNC: Network error uploading ${manga.title}, retrying in 2s... (Attempt ${retryCount + 1}/3)" }
                    delay(2.seconds)
                } else {
                    throw e // Let the caller handle permanent failures
                }
            }
        }
    }

    private fun mapToRemoteStatus(status: DomainReadingStatus): String {
        return when (status) {
            DomainReadingStatus.READING -> "READING"
            DomainReadingStatus.COMPLETED -> "COMPLETED"
            DomainReadingStatus.DROPPED -> "DROPPED"
            DomainReadingStatus.PLAN_TO_READ -> "PLAN_TO_READ"
        }
    }

    suspend fun restoreRemoteMangaLocally(remote: UserLibraryItem) {
        val status = mapFromRemoteStatus(remote.status)
        val localManga = mangaRepository.getMangaByUrlAndSourceId(remote.mangaUrl, remote.sourceId)

        if (localManga != null) {
            // Update existing
            mangaRepository.update(
                tachiyomi.domain.manga.model.MangaUpdate(
                    id = localManga.id,
                    favorite = true,
                    readingStatus = status
                )
            )
        } else {
            // Create new from network info
            val newManga = Manga.create().copy(
                url = remote.mangaUrl,
                title = remote.title,
                source = remote.sourceId,
                favorite = true,
                thumbnailUrl = remote.thumbnailUrl,
                readingStatus = status,
                initialized = false
            )
            mangaRepository.insertNetworkManga(listOf(newManga))
        }
    }

    private fun mapFromRemoteStatus(remoteStatus: String): DomainReadingStatus {
        return when (remoteStatus.uppercase()) {
            "READING" -> DomainReadingStatus.READING
            "COMPLETED" -> DomainReadingStatus.COMPLETED
            "DROPPED" -> DomainReadingStatus.DROPPED
            "PLAN_TO_READ" -> DomainReadingStatus.PLAN_TO_READ
            else -> DomainReadingStatus.READING
        }
    }

    suspend fun updateChapterProgress(manga: Manga, chapter: Chapter, page: Int, isRead: Boolean) {
        // Just wrap the plural version for a single item.
        // We override the progress data to use the provided 'page' and 'isRead'
        // instead of relying on the Chapter object fields which might be stale.
        updateChaptersProgress(manga, listOf(chapter.copy(lastPageRead = page.toLong(), read = isRead)))
    }

    /**
     * @return true if ALL batches were successfully synced.
     */
    suspend fun updateChaptersProgress(manga: Manga, chapters: List<Chapter>): Boolean {
        val session = supabase.auth.currentSessionOrNull()
        if (session == null) {
            logcat(LogPriority.WARN) { "AOI_SYNC: Aborting progress sync, no active session found" }
            return false
        }

        val remoteSourceId = UUID.nameUUIDFromBytes("source:${manga.source}:${manga.url}".toByteArray()).toString()
        val now = java.time.Instant.now().toString()
        var allSuccess = true

        chapters.chunked(BATCH_SIZE).forEach { batch ->
            // Proactive session refresh
            ensureValidSession()

            var retryCount = 0
            var batchSuccess = false
            val maxRetries = 2

            while (!batchSuccess && retryCount <= maxRetries) {
                val remoteChapters = batch.map { chapter ->
                    val remoteChapterId = UUID.nameUUIDFromBytes("chapter:${manga.source}:${manga.url}:${chapter.url}".toByteArray()).toString()
                    val dateUpload = if (chapter.dateUpload > 0) java.time.Instant.ofEpochMilli(chapter.dateUpload).toString() else now
                    ChapterUpsertRemote(
                        id = remoteChapterId,
                        mangaSourceId = remoteSourceId,
                        sourceChapterId = chapter.url,
                        name = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        chapterLabel = null,
                        scanlator = chapter.scanlator,
                        uploadedAt = dateUpload
                    )
                }

                val progressList = batch.map { chapter ->
                    val remoteChapterId = UUID.nameUUIDFromBytes("chapter:${manga.source}:${manga.url}:${chapter.url}".toByteArray()).toString()
                    ChapterProgressRemote(
                        userId = session.user!!.id,
                        chapterId = remoteChapterId,
                        mangaId = remoteSourceId,
                        lastPageRead = chapter.lastPageRead.toInt(),
                        read = chapter.read,
                        updatedAt = now
                    )
                }

                try {
                    // Step 1: Bulk Upsert Chapters
                    supabase.postgrest["chapters"].upsert(remoteChapters) {
                        onConflict = "id"
                    }

                    // Step 2: Bulk Upsert Progress
                    supabase.postgrest["user_chapter_progress"].upsert(progressList) {
                        onConflict = "user_id,chapter_id"
                    }
                    batchSuccess = true
                } catch (e: RestException) {
                    if (e.statusCode == 42501 && retryCount == 0) {
                        sessionMutex.withLock {
                            logcat(LogPriority.WARN) { "AOI_SYNC: Unauthorized (42501), attempting session refresh and retry..." }
                            try {
                                supabase.auth.refreshCurrentSession()
                                retryCount++
                            } catch (refreshEx: Exception) {
                                logcat(LogPriority.ERROR, refreshEx) { "AOI_SYNC: Session refresh failed during retry" }
                                break
                            }
                        }
                    } else {
                        logcat(LogPriority.ERROR, e) { "Supabase: Error batch updating chapter progress for ${manga.title}. Status: ${e.statusCode}" }
                        break
                    }
                } catch (e: Exception) {
                    val isNetworkError = e is java.io.IOException || e.message?.contains("Unable to resolve host") == true || e.message?.contains("timeout") == true

                    if (isNetworkError && retryCount < maxRetries) {
                        retryCount++
                        logcat(LogPriority.WARN) { "AOI_SYNC: Network error syncing progress for ${manga.title}, retrying in 2s... (Attempt ${retryCount + 1}/3)" }
                        delay(2.seconds)
                    } else {
                        logcat(LogPriority.ERROR, e) { "Supabase: Unexpected error batch updating chapter progress for ${manga.title}" }
                        break
                    }
                }
            }
            if (!batchSuccess) allSuccess = false
        }
        return allSuccess
    }

    /**
     * Syncs ALL chapters of provided mangas to the cloud.
     * @return true if every chapter of every manga was successfully synced.
     */
    suspend fun backfillAllProgress(mangaList: List<Manga>, getChapters: GetChaptersByMangaId): Boolean {
        val userId = supabase.auth.currentUserOrNull()?.id ?: return false
        logcat(LogPriority.INFO) { "Sync: Starting full progress backfill for user $userId" }

        var mangasSynced = 0
        var mangasFailed = 0
        var chaptersSynced = 0
        var chaptersFailed = 0

        mangaList.forEach { manga ->
            val chapters = getChapters.await(manga.id)
            // No filter: we want to sync the entire state of the library

            if (chapters.isNotEmpty()) {
                logcat(LogPriority.DEBUG) { "Sync: Backfilling all ${chapters.size} chapters for ${manga.title}" }
                val success = updateChaptersProgress(manga, chapters)
                if (success) {
                    mangasSynced++
                    chaptersSynced += chapters.size
                } else {
                    mangasFailed++
                    chaptersFailed += chapters.size
                }
            } else {
                mangasSynced++ // Technically nothing to do
            }
        }

        if (mangasFailed == 0) {
            logcat(LogPriority.INFO) { "Sync: Completed. Mangas: $mangasSynced synced. Chapters: $chaptersSynced synced." }
        } else {
            logcat(LogPriority.WARN) { "Sync: Completed with errors. Mangas: $mangasSynced synced, $mangasFailed failed. Chapters: $chaptersSynced synced, $chaptersFailed failed." }
        }

        return mangasFailed == 0
    }

    suspend fun reconcileLocalToCloud(
        userId: String,
        localMangaList: List<Manga>,
        getChapters: GetChaptersByMangaId,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMangaFailed: (mangaTitle: String) -> Unit = {}
    ) {
        // AOI: Proactive session refresh before starting reconciliation
        ensureValidSession()

        // user_library uses "manga:" prefix
        val currentMangaHashes = localMangaList.map { manga ->
            UUID.nameUUIDFromBytes("manga:${manga.source}:${manga.url}".toByteArray()).toString()
        }.toSet()

        // user_chapter_progress uses "source:" prefix
        val currentSourceHashes = localMangaList.map { manga ->
            UUID.nameUUIDFromBytes("source:${manga.source}:${manga.url}".toByteArray()).toString()
        }.toSet()

        // 1. Cleanup user_library (uses "manga:" hashes)
        try {
            if (localMangaList.isEmpty()) {
                supabase.postgrest["user_library"].delete {
                    filter { eq("user_id", userId) }
                }
                logcat(LogPriority.INFO) { "Sync: user_library full wipe finished (empty favorites)" }
            } else {
                val libraryMangaIdsInCloud = supabase.postgrest["user_library"]
                    .select(columns = Columns.list("manga_id")) {
                        filter { eq("user_id", userId) }
                    }
                    .decodeList<MangaIdRemote>()
                    .map { it.mangaId }

                val libraryIdsToDelete = libraryMangaIdsInCloud.filter { it !in currentMangaHashes }
                if (libraryIdsToDelete.isNotEmpty()) {
                    libraryIdsToDelete.chunked(100).forEach { chunk ->
                        supabase.postgrest["user_library"].delete {
                            filter {
                                eq("user_id", userId)
                                isIn("manga_id", chunk)
                            }
                        }
                    }
                    logcat(LogPriority.INFO) { "Sync: user_library cleanup finished" }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync: user_library cleanup failed" }
        }

        // 2. Cleanup user_chapter_progress (uses "source:" hashes)
        try {
            if (localMangaList.isEmpty()) {
                supabase.postgrest["user_chapter_progress"].delete {
                    filter { eq("user_id", userId) }
                }
                logcat(LogPriority.INFO) { "Sync: user_chapter_progress full wipe finished (empty favorites)" }
            } else {
                val progressMangaIdsInCloud = supabase.postgrest["user_chapter_progress"]
                    .select(columns = Columns.list("manga_id")) {
                        filter { eq("user_id", userId) }
                    }
                    .decodeList<MangaIdRemote>()
                    .map { it.mangaId }
                    .distinct()

                val progressIdsToDelete = progressMangaIdsInCloud.filter { it !in currentSourceHashes }
                if (progressIdsToDelete.isNotEmpty()) {
                    progressIdsToDelete.chunked(100).forEach { chunk ->
                        supabase.postgrest["user_chapter_progress"].delete {
                            filter {
                                eq("user_id", userId)
                                isIn("manga_id", chunk)
                            }
                        }
                    }
                    logcat(LogPriority.INFO) { "Sync: user_chapter_progress cleanup finished" }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync: user_chapter_progress cleanup failed" }
        }

        // 3. Upsert all current favorites to user_library and related tables
        localMangaList.forEachIndexed { index, manga ->
            onProgress(index + 1, localMangaList.size)
            try {
                uploadMangaSync(userId, manga)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: Failed to upsert library entry for ${manga.title}: ${e.message}" }
                onMangaFailed(manga.title)
            }
        }

        // Upsert all current favorites progress
        backfillAllProgress(localMangaList, getChapters)
    }

    suspend fun reconcileCloudToLocal(
        userId: String,
        updateMangaFromRemote: mihon.domain.source.interactor.UpdateMangaFromRemote,
        updateChapter: tachiyomi.domain.chapter.interactor.UpdateChapter,
        chapterRepository: tachiyomi.domain.chapter.repository.ChapterRepository,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMangaFailed: (mangaTitle: String) -> Unit = {}
    ) {
        // 1. Fetch cloud library
        val remoteItems = getUserLibrary(userId).getOrNull() ?: return
        val remoteMangaUrls = remoteItems.map { it.mangaUrl }.toSet()

        // 2. Sync favorites (Add missing and update existing)
        remoteItems.forEach { remote ->
            restoreRemoteMangaLocally(remote)
        }

        // 3. Sync favorites (Remove local-only)
        val initialLocal = mangaRepository.getFavorites()
        val toUnfavorite = initialLocal
            .filter { it.url !in remoteMangaUrls }
            .map { tachiyomi.domain.manga.model.MangaUpdate(id = it.id, favorite = false) }

        if (toUnfavorite.isNotEmpty()) {
            mangaRepository.updateAll(toUnfavorite)
            logcat(LogPriority.INFO) { "Sync: Removed ${toUnfavorite.size} local-only favorites" }
        }

        // 4. Refresh chapters from source for all active mangas
        val activeMangas = mangaRepository.getFavorites()
        activeMangas.forEach { manga ->
            try {
                updateMangaFromRemote(manga, fetchChapters = true)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Sync: Source offline or error for ${manga.title}, skipping refresh" }
            }
        }

        // 5. Fetch all progress from cloud (Split into 2 queries to avoid join issues)
        val progressList = try {
            supabase.postgrest["user_chapter_progress"]
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeList<ChapterProgressRemote>()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync: Failed to fetch remote progress" }
            return
        }

        if (progressList.isEmpty()) {
            logcat(LogPriority.INFO) { "Sync: No remote progress found" }
            return
        }

        // 5.1 Fetch associated chapter metadata for these progress entries in chunks to avoid URL length limits
        val chapterIds = progressList.map { it.chapterId }.distinct()
        val chapterMetadataMap = mutableMapOf<String, ChapterRemote>()

        try {
            chapterIds.chunked(100).forEach { chunk ->
                val results = supabase.postgrest["chapters"]
                    .select {
                        filter { isIn("id", chunk) }
                    }
                    .decodeList<ChapterRemote>()
                chapterMetadataMap.putAll(results.associateBy { it.id })
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync: Failed to fetch chapter metadata for progress" }
            return
        }

        // 6. Apply progress locally
        activeMangas.forEachIndexed { index, manga ->
            onProgress(index + 1, activeMangas.size)
            val mangaRemoteId = UUID.nameUUIDFromBytes("manga:${manga.source}:${manga.url}".toByteArray()).toString()
            val entriesForManga = progressList.filter { it.mangaId == mangaRemoteId }

            if (entriesForManga.isNotEmpty()) {
                try {
                    // Refresh chapters from source first to ensure matching works
                    updateMangaFromRemote(manga, fetchChapters = true)

                    val updates = entriesForManga.mapNotNull { entry ->
                        val chapterMetadata = chapterMetadataMap[entry.chapterId] ?: return@mapNotNull null
                        val localChapter = chapterRepository.getChapterByUrlAndMangaId(chapterMetadata.sourceChapterId, manga.id)
                        if (localChapter != null && (localChapter.read != entry.read || localChapter.lastPageRead != entry.lastPageRead.toLong())) {
                            tachiyomi.domain.chapter.model.ChapterUpdate(
                                id = localChapter.id,
                                read = entry.read,
                                lastPageRead = entry.lastPageRead.toLong()
                            )
                        } else {
                            null
                        }
                    }
                    if (updates.isNotEmpty()) {
                        updateChapter.awaitAll(updates)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Sync: Failed to import progress for ${manga.title}" }
                    onMangaFailed(manga.title)
                }
            }
        }
        logcat(LogPriority.INFO) { "Sync: Import reconciliation completed" }
    }
}

@Serializable
data class UserLibraryEntrySimple(
    @SerialName("user_id") val userId: String,
    @SerialName("manga_id") val mangaId: String,
    val status: String,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("source_id") val sourceId: String,
    @SerialName("added_at") val addedAt: String
)

@Serializable
data class MangaIdRemote(
    @SerialName("manga_id") val mangaId: String
)

@Serializable
data class MangaSourceSyncRemote(
    val id: String,
    @SerialName("manga_id") val mangaId: String,
    @SerialName("extension_id") val extensionId: String,
    @SerialName("source_manga_id") val sourceMangaId: String
)

// --- Remote Data Models (Mapping Supabase Schema) ---

@Serializable
data class UserLibraryEntryRemote(
    @SerialName("user_id") val userId: String,
    @SerialName("manga_id") val mangaId: String,
    val status: String,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("source_id") val sourceId: String,
    @SerialName("last_chapter_id") val lastChapterId: String?,
    @SerialName("last_page") val lastPage: Int?,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    @SerialName("added_at") val addedAt: String? = null,

    // Joins
    val manga: MangaRemote,
    @SerialName("manga_sources") val source: MangaSourceRemote,
    @SerialName("chapters") val lastChapter: ChapterRemote? = null
)

@Serializable
data class MangaRemote(
    val id: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val status: String? = null,
)

@Serializable
data class MangaSourceRemote(
    val id: String,
    @SerialName("extension_id") val extensionId: String,
    @SerialName("source_manga_id") val sourceMangaId: String = "",
    @SerialName("extensions") val extension: ExtensionRemote
)

@Serializable
data class ExtensionRemote(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean
)

@Serializable
data class ChapterRemote(
    val id: String,
    @SerialName("manga_source_id") val mangaSourceId: String,
    @SerialName("source_chapter_id") val sourceChapterId: String,
    @SerialName("chapter_number") val chapterNumber: Double?,
    val name: String? = null,
    @SerialName("chapter_label") val chapterLabel: String? = null,
    val scanlator: String? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null
)

@Serializable
data class ChapterUpsertRemote(
    val id: String,
    @SerialName("manga_source_id") val mangaSourceId: String,
    @SerialName("source_chapter_id") val sourceChapterId: String,
    val name: String,
    @SerialName("chapter_number") val chapterNumber: Double,
    @SerialName("chapter_label") val chapterLabel: String?,
    val scanlator: String?,
    @SerialName("uploaded_at") val uploadedAt: String?
)

@Serializable
data class ChapterProgressRemote(
    @SerialName("user_id") val userId: String,
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("manga_id") val mangaId: String,
    @SerialName("last_page_read") val lastPageRead: Int,
    val read: Boolean,
    @SerialName("updated_at") val updatedAt: String
)

// --- UI Model ---

@Serializable
data class UserLibraryItem(
    val mangaId: String,
    val title: String,
    val thumbnailUrl: String?,
    val status: String, // 'reading' | 'completed' | 'dropped' | 'plan_to_read'
    val isFavorite: Boolean,
    val extensionName: String,
    val sourceId: Long,
    val mangaUrl: String,
    val lastChapterNumber: Double?,
    val lastChapterLabel: String?,
    val lastPage: Int?,
    val lastReadAt: String?
)
