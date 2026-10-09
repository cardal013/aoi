package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.manga.model.ReadingStatus as DomainReadingStatus

@Inject
@SingleIn(AppScope::class)
class LibrarySupabaseRepository(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
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
            Result.success(fetchRemoteLibrary(userId, updatedSince = null).map { it.toItem() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Supabase: Error fetching user library" }
            Result.failure(e)
        }
    }

    private fun UserLibraryEntryRemote.toItem() = UserLibraryItem(
        mangaId = mangaId,
        title = manga.title,
        thumbnailUrl = manga.thumbnailUrl,
        status = status,
        isFavorite = isFavorite,
        extensionName = source.extension.name,
        sourceId = source.extensionId.toLongOrNull() ?: -1L,
        mangaUrl = source.sourceMangaId,
        lastChapterNumber = lastChapter?.chapterNumber,
        lastChapterLabel = lastChapter?.chapterLabel,
        lastPage = lastPage,
        lastReadAt = lastReadAt,
    )

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

    /**
     * @return o id local do manga se foi criado ou alterado, ou null se já estava igual à cloud.
     */
    suspend fun restoreRemoteMangaLocally(remote: UserLibraryItem): Long? {
        val status = mapFromRemoteStatus(remote.status)
        val localManga = mangaRepository.getMangaByUrlAndSourceId(remote.mangaUrl, remote.sourceId)

        if (localManga != null) {
            if (localManga.favorite && localManga.readingStatus == status) return null

            // Update existing
            mangaRepository.update(
                tachiyomi.domain.manga.model.MangaUpdate(
                    id = localManga.id,
                    favorite = true,
                    readingStatus = status
                )
            )
            return localManga.id
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
            return mangaRepository.insertNetworkManga(listOf(newManga)).firstOrNull()?.id
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
    suspend fun backfillAllProgress(
        mangaList: List<Manga>,
        getChapters: GetChaptersByMangaId,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Boolean {
        val userId = supabase.auth.currentUserOrNull()?.id ?: return false
        logcat(LogPriority.INFO) { "Sync: Starting full progress backfill for user $userId" }

        var mangasSynced = 0
        var mangasFailed = 0
        var chaptersSynced = 0
        var chaptersFailed = 0

        mangaList.forEachIndexed { index, manga ->
            onProgress(index + 1, mangaList.size)
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

    // --- IDs remotos (determinísticos, ver sync-supabase no vault) ---

    private fun mangaRemoteId(manga: Manga): String =
        UUID.nameUUIDFromBytes("manga:${manga.source}:${manga.url}".toByteArray()).toString()

    private fun sourceRemoteId(manga: Manga): String =
        UUID.nameUUIDFromBytes("source:${manga.source}:${manga.url}".toByteArray()).toString()

    private fun chapterRemoteId(manga: Manga, chapter: Chapter): String =
        UUID.nameUUIDFromBytes("chapter:${manga.source}:${manga.url}:${chapter.url}".toByteArray()).toString()

    private fun isNetworkError(e: Exception): Boolean =
        e is IOException || e.message?.contains("Unable to resolve host") == true || e.message?.contains("timeout") == true

    /**
     * Repete o pedido em erros de rede (2 vezes, 2 s) e uma vez depois de renovar a sessão se esta tiver expirado.
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var networkRetries = 0
        var sessionRefreshed = false
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val unauthorized = e is RestException &&
                    (e.statusCode == 401 || e.message?.contains("42501") == true || e.message?.contains("JWT") == true)
                when {
                    unauthorized && !sessionRefreshed -> {
                        sessionRefreshed = true
                        sessionMutex.withLock {
                            logcat(LogPriority.WARN) { "AOI_SYNC: Sessão recusada, a renovar e repetir" }
                            supabase.auth.refreshCurrentSession()
                        }
                    }
                    isNetworkError(e) && networkRetries < 2 -> {
                        networkRetries++
                        logcat(LogPriority.WARN) { "AOI_SYNC: Erro de rede, nova tentativa em 2s ($networkRetries/2)" }
                        delay(2.seconds)
                    }
                    else -> throw e
                }
            }
        }
    }

    /**
     * Upload completo (primeiro sync, ou "Full resync"): espelha a biblioteca local na cloud.
     * Apaga da cloud o que já não é favorito local e envia todos os favoritos com o respetivo progresso.
     */
    suspend fun reconcileLocalToCloud(
        userId: String,
        localMangaList: List<Manga>,
        getChapters: GetChaptersByMangaId,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMangaFailed: (mangaTitle: String) -> Unit = {}
    ): SyncResult {
        // user_library uses "manga:" prefix
        val currentMangaHashes = localMangaList.map { mangaRemoteId(it) }.toSet()

        // user_chapter_progress uses "source:" prefix
        val currentSourceHashes = localMangaList.map { sourceRemoteId(it) }.toSet()

        var cleanupFailed = false
        var mangasRemoved = 0

        // AOI: Proactive session refresh before starting reconciliation
        ensureValidSession()

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
                        order("manga_id", Order.ASCENDING)
                    }
                    .decodeList<MangaIdRemote>()
                    .map { it.mangaId }

                val libraryIdsToDelete = libraryMangaIdsInCloud.filter { it !in currentMangaHashes }
                if (libraryIdsToDelete.isNotEmpty()) {
                    libraryIdsToDelete.chunked(UPSERT_BATCH_SIZE).forEach { chunk ->
                        supabase.postgrest["user_library"].delete {
                            filter {
                                eq("user_id", userId)
                                isIn("manga_id", chunk)
                            }
                        }
                    }
                    mangasRemoved = libraryIdsToDelete.size
                    logcat(LogPriority.INFO) { "Sync: user_library cleanup finished" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cleanupFailed = true
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
                // Paginado: o PostgREST devolve no máximo 1000 linhas por pedido
                val progressMangaIdsInCloud = mutableSetOf<String>()
                var offset = 0
                while (true) {
                    val chunk = supabase.postgrest["user_chapter_progress"]
                        .select(columns = Columns.list("manga_id")) {
                            filter { eq("user_id", userId) }
                            order("chapter_id", Order.ASCENDING)
                            range(offset.toLong(), (offset + PAGE_SIZE - 1).toLong())
                        }
                        .decodeList<MangaIdRemote>()
                    progressMangaIdsInCloud.addAll(chunk.map { it.mangaId })
                    if (chunk.size < PAGE_SIZE) break
                    offset += PAGE_SIZE
                }

                val progressIdsToDelete = progressMangaIdsInCloud.filter { it !in currentSourceHashes }
                if (progressIdsToDelete.isNotEmpty()) {
                    progressIdsToDelete.chunked(UPSERT_BATCH_SIZE).forEach { chunk ->
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cleanupFailed = true
            logcat(LogPriority.ERROR, e) { "Sync: user_chapter_progress cleanup failed" }
        }

        // 3. Sequential Sync: Metadata then Progress per Manga
        var failed = 0
        var mangasSent = 0
        var chaptersSent = 0
        localMangaList.forEachIndexed { index, manga ->
            onProgress(index + 1, localMangaList.size)
            try {
                // Ensure manga and user_library entry exists
                uploadMangaSync(userId, manga)
                mangasSent++

                // Immediately sync chapters for this manga
                val chapters = getChapters.await(manga.id)

                logcat(LogPriority.INFO) {
                    "AOI_SYNC: getChapters result | " +
                    "manga=${manga.title} | " +
                    "mangaId=${manga.id} | " +
                    "count=${chapters.size} | " +
                    "readCount=${chapters.count { it.read }}"
                }

                if (chapters.isNotEmpty()) {
                    if (updateChaptersProgress(manga, chapters)) {
                        chaptersSent += chapters.size
                    } else {
                        failed++
                        onMangaFailed(manga.title)
                    }
                } else {
                    logcat(LogPriority.WARN) {
                        "AOI_SYNC: SKIPPING chapter sync — empty chapter list | " +
                        "manga=${manga.title}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: Failed to reconcile ${manga.title}: ${e.message}" }
                failed++
                onMangaFailed(manga.title)
            }
        }

        val result = SyncResult(
            ok = failed == 0 && !cleanupFailed,
            mangasSent = mangasSent,
            chaptersSent = chaptersSent,
            mangasRemoved = mangasRemoved,
        )
        logcat(LogPriority.INFO) { "Sync: upload COMPLETO | ${result.summary()} | falhas=$failed | limpezaFalhou=$cleanupFailed" }
        return result
    }

    /**
     * Upload incremental: envia só o que mudou localmente desde [since] (ms).
     * Ordem (para evitar o erro de FK 23503): extensions → manga → manga_sources → user_library,
     * remoções, e por fim chapters → user_chapter_progress. Lotes de [UPSERT_BATCH_SIZE].
     * Só devolve `ok = true` se tudo correu bem; o chamador só então avança o timestamp do último sync.
     */
    suspend fun syncLocalChangesToCloud(
        userId: String,
        since: Long,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMangaFailed: (mangaTitle: String) -> Unit = {}
    ): SyncResult {
        val dirtyMangas = mangaRepository.getFavoritesModifiedSince(since)
        val removedMangas = mangaRepository.getRemovedFromLibrarySince(since)

        val mangaCache = (dirtyMangas + removedMangas).associateBy { it.id }.toMutableMap()
        val dirtyChapters = chapterRepository.getFavoriteChaptersModifiedSince(since).mapNotNull { chapter ->
            val manga = mangaCache[chapter.mangaId]
                ?: runCatching { mangaRepository.getMangaById(chapter.mangaId) }.getOrNull()
                    ?.also { mangaCache[it.id] = it }
            manga?.let { it to chapter }
        }

        val total = dirtyMangas.size + removedMangas.size + dirtyChapters.size
        logcat(LogPriority.INFO) {
            "Sync: upload INCREMENTAL | since=$since | mangas=${dirtyMangas.size} | " +
                "removidos=${removedMangas.size} | capitulos=${dirtyChapters.size}"
        }
        if (total == 0) {
            logcat(LogPriority.INFO) { "Sync: upload INCREMENTAL | nada para enviar" }
            return SyncResult(ok = true)
        }

        ensureValidSession()

        val failedTitles = linkedSetOf<String>()
        fun fail(title: String) {
            if (failedTitles.add(title)) onMangaFailed(title)
        }

        var done = 0
        var mangasSent = 0
        var mangasRemoved = 0
        var chaptersSent = 0
        var failedRecords = 0

        // 1. extensions → manga → manga_sources → user_library
        dirtyMangas.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            try {
                uploadMangaBatch(userId, batch)
                mangasSent += batch.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Sync: lote de ${batch.size} mangas falhou, a enviar um a um" }
                batch.forEach { manga ->
                    try {
                        uploadMangaSync(userId, manga)
                        mangasSent++
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        logcat(LogPriority.ERROR, e2) { "Sync: falhou o upload de ${manga.title}" }
                        failedRecords++
                        fail(manga.title)
                    }
                }
            }
            done += batch.size
            onProgress(done, total)
        }

        // 2. Remoções: só os registos daquele manga, sem apagar e recriar o resto
        removedMangas.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            try {
                withRetry { removeMangasFromCloud(userId, batch) }
                mangasRemoved += batch.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: falhou a remoção de ${batch.size} mangas da cloud" }
                failedRecords += batch.size
                batch.forEach { fail(it.title) }
            }
            done += batch.size
            onProgress(done, total)
        }

        // 3. chapters → user_chapter_progress
        dirtyChapters.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            try {
                uploadProgressBatch(userId, batch)
                chaptersSent += batch.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: lote de ${batch.size} capítulos falhou" }
                failedRecords += batch.size
                batch.forEach { fail(it.first.title) }
            }
            done += batch.size
            onProgress(done, total)
        }

        val result = SyncResult(
            ok = failedRecords == 0,
            mangasSent = mangasSent,
            chaptersSent = chaptersSent,
            mangasRemoved = mangasRemoved,
        )
        logcat(LogPriority.INFO) { "Sync: upload INCREMENTAL | ${result.summary()} | falhas=$failedRecords" }
        return result
    }

    private suspend fun uploadMangaBatch(userId: String, mangas: List<Manga>) {
        val now = Instant.now().toString()
        withRetry {
            supabase.postgrest["extensions"].upsert(
                mangas.distinctBy { it.source }.map {
                    ExtensionRemote(
                        id = it.source.toString(),
                        name = sourceManager.getOrStub(it.source).name,
                        isActive = true,
                    )
                },
            )
            supabase.postgrest["manga"].upsert(
                mangas.map {
                    MangaRemote(
                        id = mangaRemoteId(it),
                        title = it.title,
                        author = it.author,
                        artist = it.artist,
                        thumbnailUrl = it.thumbnailUrl,
                    )
                },
            )
            supabase.postgrest["manga_sources"].upsert(
                mangas.map {
                    MangaSourceSyncRemote(
                        id = sourceRemoteId(it),
                        mangaId = mangaRemoteId(it),
                        extensionId = it.source.toString(),
                        sourceMangaId = it.url,
                    )
                },
            )
            supabase.postgrest["user_library"].upsert(
                mangas.map {
                    UserLibraryEntrySimple(
                        userId = userId,
                        mangaId = mangaRemoteId(it),
                        status = mapToRemoteStatus(it.readingStatus),
                        isFavorite = it.favorite,
                        sourceId = sourceRemoteId(it),
                        addedAt = now,
                    )
                },
            )
        }
    }

    private suspend fun removeMangasFromCloud(userId: String, mangas: List<Manga>) {
        supabase.postgrest["user_library"].delete {
            filter {
                eq("user_id", userId)
                isIn("manga_id", mangas.map { mangaRemoteId(it) })
            }
        }
        supabase.postgrest["user_chapter_progress"].delete {
            filter {
                eq("user_id", userId)
                isIn("manga_id", mangas.map { sourceRemoteId(it) })
            }
        }
    }

    private suspend fun uploadProgressBatch(userId: String, items: List<Pair<Manga, Chapter>>) {
        val now = Instant.now().toString()
        val remoteChapters = items.map { (manga, chapter) ->
            ChapterUpsertRemote(
                id = chapterRemoteId(manga, chapter),
                mangaSourceId = sourceRemoteId(manga),
                sourceChapterId = chapter.url,
                name = chapter.name,
                chapterNumber = chapter.chapterNumber,
                chapterLabel = null,
                scanlator = chapter.scanlator,
                uploadedAt = if (chapter.dateUpload > 0) Instant.ofEpochMilli(chapter.dateUpload).toString() else now,
            )
        }.distinctBy { it.id }
        val remoteProgress = items.map { (manga, chapter) ->
            ChapterProgressRemote(
                userId = userId,
                chapterId = chapterRemoteId(manga, chapter),
                mangaId = sourceRemoteId(manga),
                lastPageRead = chapter.lastPageRead.toInt(),
                read = chapter.read,
                updatedAt = now,
            )
        }.distinctBy { it.chapterId }

        suspend fun push() = withRetry {
            supabase.postgrest["chapters"].upsert(remoteChapters) {
                onConflict = "id"
            }
            supabase.postgrest["user_chapter_progress"].upsert(remoteProgress) {
                onConflict = "user_id,chapter_id"
            }
        }

        try {
            push()
        } catch (e: RestException) {
            // 23503 (FK): a fonte do manga ainda não existe na cloud. Envia os pais e repete uma vez
            if (e.statusCode != 409 && e.message?.contains("23503") != true) throw e
            logcat(LogPriority.WARN) { "Sync: FK 23503 nos capítulos, a enviar primeiro os mangas pai" }
            items.map { it.first }.distinctBy { it.id }.forEach { uploadMangaSync(userId, it) }
            push()
        }
    }

    /**
     * Import da cloud.
     * - [since] = 0: completo (primeiro sync ou "Full resync"), a cloud substitui a biblioteca local.
     * - [since] > 0: incremental, só aplica o que mudou na cloud depois de [since] (ms, `updated_at`).
     *   Não toca no que está por enviar localmente (alterado depois de [pendingSince]).
     */
    suspend fun reconcileCloudToLocal(
        userId: String,
        since: Long,
        pendingSince: Long,
        updateChapter: UpdateChapter,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMangaFailed: (mangaTitle: String) -> Unit = {}
    ): SyncResult {
        val incremental = since > 0L
        val updatedSince = if (incremental) Instant.ofEpochMilli(since - DOWNLOAD_OVERLAP_MS).toString() else null
        logcat(LogPriority.INFO) {
            "Sync: import ${if (incremental) "INCREMENTAL" else "COMPLETO"} | since=$since | updatedSince=$updatedSince"
        }

        ensureValidSession()

        val failedTitles = linkedSetOf<String>()
        fun fail(title: String) {
            if (failedTitles.add(title)) onMangaFailed(title)
        }

        // Pendentes: alterações locais ainda não enviadas. Têm de ser lidas antes de aplicar o que vem da cloud
        val pendingMangaIds = if (incremental) {
            mangaRepository.getFavoritesModifiedSince(pendingSince).map { it.id }.toSet()
        } else {
            emptySet()
        }
        val pendingChapterIds = if (incremental) {
            chapterRepository.getFavoriteChaptersModifiedSince(pendingSince).map { it.id }.toSet()
        } else {
            emptySet()
        }

        // 1. Ler o que mudou na biblioteca da cloud (e, para detetar remoções, os URLs de tudo o que lá existe)
        val remoteItems = try {
            fetchRemoteLibrary(userId, updatedSince).map { it.toItem() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync: falhou a leitura da biblioteca na cloud" }
            return SyncResult(ok = false)
        }
        val remoteUrls: Set<String> = if (incremental) {
            try {
                fetchRemoteLibraryUrls(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: falhou a leitura da lista de mangas na cloud" }
                return SyncResult(ok = false)
            }
        } else {
            remoteItems.map { it.mangaUrl }.toSet()
        }

        // 2. Ler o progresso alterado (paginado de 1000 em 1000) e os metadados dos capítulos respetivos
        val progressList = try {
            fetchRemoteProgress(userId, updatedSince)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync: Failed to fetch remote progress" }
            return SyncResult(ok = false)
        }
        val chapterMetadataMap = mutableMapOf<String, ChapterRemote>()
        try {
            progressList.map { it.chapterId }.distinct().chunked(UPSERT_BATCH_SIZE).forEach { chunk ->
                val results = supabase.postgrest["chapters"]
                    .select {
                        filter { isIn("id", chunk) }
                    }
                    .decodeList<ChapterRemote>()
                chapterMetadataMap.putAll(results.associateBy { it.id })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync: Failed to fetch chapter metadata for progress" }
            return SyncResult(ok = false)
        }

        var librariesApplied = 0
        var unfavorited = 0
        var chaptersApplied = 0
        val touchedMangaIds = mutableListOf<Long>()

        // 3. Favoritos: acrescentar os que faltam e atualizar o estado
        remoteItems.forEach { remote ->
            try {
                val local = mangaRepository.getMangaByUrlAndSourceId(remote.mangaUrl, remote.sourceId)
                if (local != null && local.id in pendingMangaIds) return@forEach
                restoreRemoteMangaLocally(remote)?.let {
                    librariesApplied++
                    touchedMangaIds.add(it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Sync: Failed to import ${remote.title}" }
                fail(remote.title)
            }
        }

        // 4. Remover dos favoritos locais o que já não existe na cloud (menos o que está por enviar)
        val localFavorites = mangaRepository.getFavorites()
        if (incremental && remoteUrls.isEmpty() && localFavorites.isNotEmpty()) {
            logcat(LogPriority.WARN) { "Sync: cloud sem mangas, a ignorar remoções locais por segurança" }
        } else {
            val toUnfavorite = localFavorites.filter { it.url !in remoteUrls && it.id !in pendingMangaIds }
            if (toUnfavorite.isNotEmpty()) {
                mangaRepository.updateAll(
                    toUnfavorite.map { tachiyomi.domain.manga.model.MangaUpdate(id = it.id, favorite = false) },
                )
                unfavorited = toUnfavorite.size
                touchedMangaIds.addAll(toUnfavorite.map { it.id })
                logcat(LogPriority.INFO) { "Sync: Removed ${toUnfavorite.size} local-only favorites" }
            }
        }
        // O que veio da cloud não é uma alteração local por enviar
        mangaRepository.clearCloudDirty(touchedMangaIds)

        // 5. Progresso
        val activeMangas = mangaRepository.getFavorites().associateBy { sourceRemoteId(it) }
        val progressByManga = progressList.groupBy { it.mangaId }.filterKeys { it in activeMangas }
        var index = 0
        progressByManga.forEach { (mangaSourceId, entries) ->
            val manga = activeMangas.getValue(mangaSourceId)
            onProgress(++index, progressByManga.size)
            try {
                val localChapters = chapterRepository.getChapterByMangaId(manga.id).associateBy { it.url }
                val updates = entries.mapNotNull { entry ->
                    val chapterMetadata = chapterMetadataMap[entry.chapterId] ?: return@mapNotNull null
                    val localChapter = localChapters[chapterMetadata.sourceChapterId] ?: return@mapNotNull null
                    if (localChapter.id in pendingChapterIds) return@mapNotNull null
                    if (localChapter.read != entry.read || localChapter.lastPageRead != entry.lastPageRead.toLong()) {
                        ChapterUpdate(
                            id = localChapter.id,
                            read = entry.read,
                            lastPageRead = entry.lastPageRead.toLong(),
                        )
                    } else {
                        null
                    }
                }
                if (updates.isNotEmpty()) {
                    updateChapter.awaitAll(updates)
                    chapterRepository.clearCloudDirty(updates.map { it.id })
                    chaptersApplied += updates.size
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Sync: Failed to import progress for ${manga.title}" }
                fail(manga.title)
            }
        }

        val result = SyncResult(
            ok = failedTitles.isEmpty(),
            mangasReceived = remoteItems.size,
            chaptersReceived = progressList.size,
            mangasApplied = librariesApplied + unfavorited,
            chaptersApplied = chaptersApplied,
        )
        logcat(LogPriority.INFO) {
            "Sync: import ${if (incremental) "INCREMENTAL" else "COMPLETO"} | ${result.summary()} | " +
                "removidosLocalmente=$unfavorited | falhas=${failedTitles.size}"
        }
        return result
    }

    private suspend fun fetchRemoteLibrary(userId: String, updatedSince: String?): List<UserLibraryEntryRemote> {
        suspend fun fetch(since: String?): List<UserLibraryEntryRemote> {
            val all = mutableListOf<UserLibraryEntryRemote>()
            var offset = 0
            while (true) {
                val chunk = supabase.postgrest["user_library"]
                    .select(columns = LIBRARY_COLUMNS) {
                        filter {
                            eq("user_id", userId)
                            if (since != null) gte("updated_at", since)
                        }
                        order("manga_id", Order.ASCENDING)
                        range(offset.toLong(), (offset + PAGE_SIZE - 1).toLong())
                    }
                    .decodeList<UserLibraryEntryRemote>()
                all.addAll(chunk)
                logcat(LogPriority.INFO) { "Sync: Fetched library chunk | offset=$offset | size=${chunk.size}" }
                if (chunk.size < PAGE_SIZE) break
                offset += PAGE_SIZE
            }
            return all
        }

        if (updatedSince == null) return fetch(null)
        return try {
            fetch(updatedSince)
        } catch (e: RestException) {
            // Provavelmente falta a coluna user_library.updated_at (migration por correr): lê tudo
            logcat(LogPriority.WARN, e) { "Sync: user_library sem updated_at, a ler a biblioteca toda" }
            fetch(null)
        }
    }

    private suspend fun fetchRemoteLibraryUrls(userId: String): Set<String> {
        val urls = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val chunk = supabase.postgrest["user_library"]
                .select(columns = Columns.raw("manga_id, manga_sources(source_manga_id)")) {
                    filter { eq("user_id", userId) }
                    order("manga_id", Order.ASCENDING)
                    range(offset.toLong(), (offset + PAGE_SIZE - 1).toLong())
                }
                .decodeList<LibraryUrlRemote>()
            urls.addAll(chunk.map { it.source.sourceMangaId })
            if (chunk.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return urls
    }

    private suspend fun fetchRemoteProgress(userId: String, updatedSince: String?): List<ChapterProgressRemote> {
        val progressList = mutableListOf<ChapterProgressRemote>()
        var offset = 0
        while (true) {
            val chunk = supabase.postgrest["user_chapter_progress"]
                .select {
                    filter {
                        eq("user_id", userId)
                        if (updatedSince != null) gte("updated_at", updatedSince)
                    }
                    order("updated_at", Order.ASCENDING)
                    order("chapter_id", Order.ASCENDING)
                    range(offset.toLong(), (offset + PAGE_SIZE - 1).toLong())
                }
                .decodeList<ChapterProgressRemote>()

            progressList.addAll(chunk)
            logcat(LogPriority.INFO) { "Sync: Fetched progress chunk | offset=$offset | size=${chunk.size} | totalSoFar=${progressList.size}" }

            if (chunk.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return progressList
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val UPSERT_BATCH_SIZE = 100

        // Os relógios dos telemóveis não são iguais: relê um pouco antes do último sync (é idempotente)
        const val DOWNLOAD_OVERLAP_MS = 10 * 60 * 1000L

        val LIBRARY_COLUMNS = Columns.raw(
            """
            *,
            manga(*),
            manga_sources(*, extensions(*)),
            chapters(*)
            """.trimIndent(),
        )
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
data class LibraryUrlRemote(
    @SerialName("manga_id") val mangaId: String,
    @SerialName("manga_sources") val source: SourceUrlRemote
)

@Serializable
data class SourceUrlRemote(
    @SerialName("source_manga_id") val sourceMangaId: String
)

/**
 * Resultado de um sync com a cloud. Os contadores servem para o log e para a mensagem final.
 */
data class SyncResult(
    val ok: Boolean,
    val mangasSent: Int = 0,
    val chaptersSent: Int = 0,
    val mangasRemoved: Int = 0,
    val mangasReceived: Int = 0,
    val chaptersReceived: Int = 0,
    val mangasApplied: Int = 0,
    val chaptersApplied: Int = 0,
) {
    val totalSent: Int get() = mangasSent + chaptersSent + mangasRemoved
    val totalReceived: Int get() = mangasReceived + chaptersReceived

    fun summary(): String = "enviados=$totalSent (mangas=$mangasSent, capitulos=$chaptersSent, removidos=$mangasRemoved) | " +
        "recebidos=$totalReceived (mangas=$mangasReceived, capitulos=$chaptersReceived) | " +
        "aplicados localmente=${mangasApplied + chaptersApplied}"
}

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
