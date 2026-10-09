package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.HttpRequestException
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
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.manga.model.ReadingStatus as DomainReadingStatus

@Inject
@SingleIn(AppScope::class)
class LibrarySupabaseRepository(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
) {

    private val sessionMutex = Mutex()

    // Renova a sessão antes de a usar se faltarem menos de 2 minutos para expirar
    private suspend fun ensureValidSession() {
        val currentSession = supabase.auth.currentSessionOrNull() ?: return
        if (currentSession.expiresAt.toEpochMilliseconds() - System.currentTimeMillis() >= SESSION_MARGIN_MS) return

        sessionMutex.withLock {
            val session = supabase.auth.currentSessionOrNull() ?: return@withLock
            if (session.expiresAt.toEpochMilliseconds() - System.currentTimeMillis() >= SESSION_MARGIN_MS) {
                return@withLock
            }
            try {
                supabase.auth.refreshCurrentSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "AOI_SYNC: falhou a renovação da sessão" }
            }
        }
    }

    private fun isNetworkError(e: Exception): Boolean =
        e is IOException || e is HttpRequestException ||
            e.message?.contains("Unable to resolve host") == true || e.message?.contains("timeout") == true

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

    private fun mapToRemoteStatus(status: DomainReadingStatus): String {
        return when (status) {
            DomainReadingStatus.READING -> "READING"
            DomainReadingStatus.COMPLETED -> "COMPLETED"
            DomainReadingStatus.DROPPED -> "DROPPED"
            DomainReadingStatus.PLAN_TO_READ -> "PLAN_TO_READ"
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

    /**
     * Progresso em tempo real (leitor e marcar como lido). Só para mangas da biblioteca.
     * @return true se todos os lotes foram enviados.
     */
    suspend fun updateChapterProgress(manga: Manga, chapter: Chapter, page: Int, isRead: Boolean) {
        // O capítulo pode vir desatualizado: usa a página e o estado que o leitor indicou
        updateChaptersProgress(manga, listOf(chapter.copy(lastPageRead = page.toLong(), read = isRead)))
    }

    suspend fun updateChaptersProgress(manga: Manga, chapters: List<Chapter>): Boolean {
        if (!manga.favorite) return true
        val userId = supabase.auth.currentUserOrNull()?.id ?: return false
        ensureValidSession()

        var ok = true
        chapters.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            try {
                uploadProgressBatch(userId, batch.map { manga to it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: falhou o progresso de ${manga.title}" }
                ok = false
            }
        }
        return ok
    }

    /**
     * Upload completo (primeiro sync, depois de juntar com a cloud): envia todos os favoritos locais
     * e o progresso dos capítulos lidos ou começados. Não apaga nada da cloud.
     */
    suspend fun uploadWholeLibrary(
        userId: String,
        localMangaList: List<Manga>,
        getChapters: GetChaptersByMangaId,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncResult {
        ensureValidSession()

        var failures = 0
        fun fail(title: String) {
            failures++
            logcat(LogPriority.WARN) { "Sync: falhou $title" }
        }

        val total = localMangaList.size * 2
        var done = 0
        var mangasSent = 0
        localMangaList.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            mangasSent += uploadMangas(userId, batch, ::fail)
            done += batch.size
            onProgress(done, total)
        }

        var chaptersSent = 0
        localMangaList.forEach { manga ->
            try {
                val chapters = getChapters.await(manga.id).filter { it.read || it.lastPageRead > 0 }
                chaptersSent += uploadProgress(userId, chapters.map { manga to it }, ::fail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: falhou a leitura dos capítulos de ${manga.title}" }
                fail(manga.title)
            }
            onProgress(++done, total)
        }

        val result = SyncResult(ok = failures == 0, mangasSent = mangasSent, chaptersSent = chaptersSent)
        logcat(LogPriority.INFO) { "Sync: upload COMPLETO | ${result.summary()} | falhas=$failures" }
        return result
    }

    /**
     * Upload incremental: envia só o que mudou localmente desde [since] (ms).
     * Ordem (para evitar o erro de FK 23503): extensions → manga → manga_sources → user_library,
     * remoções (incluindo mangas apagados da base de dados), e por fim chapters → user_chapter_progress.
     * Só devolve `ok = true` se tudo correu bem; o chamador só então avança o marco do último sync.
     */
    suspend fun syncLocalChangesToCloud(
        userId: String,
        since: Long,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMangaFailed: (mangaTitle: String) -> Unit = {},
    ): SyncResult {
        val dirtyMangas = mangaRepository.getFavoritesModifiedSince(since)
        val removedMangas = mangaRepository.getRemovedFromLibrarySince(since)
        val removedKeys = (
            removedMangas.map { it.source to it.url } + mangaRepository.getDeletedForCloudSince(since)
            ).distinct()

        val mangaCache = (dirtyMangas + removedMangas).associateBy { it.id }.toMutableMap()
        val dirtyChapters = chapterRepository.getFavoriteChaptersModifiedSince(since).mapNotNull { chapter ->
            val manga = mangaCache[chapter.mangaId]
                ?: runCatching { mangaRepository.getMangaById(chapter.mangaId) }.getOrNull()
                    ?.also { mangaCache[it.id] = it }
            manga?.let { it to chapter }
        }

        val total = dirtyMangas.size + removedKeys.size + dirtyChapters.size
        logcat(LogPriority.INFO) {
            "Sync: upload INCREMENTAL | since=$since | mangas=${dirtyMangas.size} | " +
                "removidos=${removedKeys.size} | capitulos=${dirtyChapters.size}"
        }
        if (total == 0) return SyncResult(ok = true)

        ensureValidSession()

        var failures = 0
        val failedTitles = linkedSetOf<String>()
        fun fail(title: String) {
            failures++
            if (failedTitles.add(title)) onMangaFailed(title)
        }

        var done = 0
        var mangasSent = 0
        var mangasRemoved = 0

        dirtyMangas.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            mangasSent += uploadMangas(userId, batch, ::fail)
            done += batch.size
            onProgress(done, total)
        }

        // Remoções: só os registos daqueles mangas, sem apagar e recriar o resto
        removedKeys.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            try {
                withRetry { removeMangasFromCloud(userId, batch) }
                mangasRemoved += batch.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: falhou a remoção de ${batch.size} mangas da cloud" }
                val titles = removedMangas.associate { (it.source to it.url) to it.title }
                batch.forEach { fail(titles[it] ?: it.second) }
            }
            done += batch.size
            onProgress(done, total)
        }

        val chaptersSent = uploadProgress(userId, dirtyChapters, ::fail)
        onProgress(total, total)

        val result = SyncResult(
            ok = failures == 0,
            mangasSent = mangasSent,
            chaptersSent = chaptersSent,
            mangasRemoved = mangasRemoved,
        )
        logcat(LogPriority.INFO) { "Sync: upload INCREMENTAL | ${result.summary()} | falhas=$failures" }
        return result
    }

    /** Envia um lote de mangas; se o lote falhar tenta um a um. Devolve quantos foram enviados. */
    private suspend fun uploadMangas(userId: String, mangas: List<Manga>, fail: (String) -> Unit): Int {
        try {
            uploadMangaBatch(userId, mangas)
            return mangas.size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Sync: lote de ${mangas.size} mangas falhou, a enviar um a um" }
        }
        var sent = 0
        mangas.forEach { manga ->
            try {
                uploadMangaBatch(userId, listOf(manga))
                sent++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: falhou o upload de ${manga.title}" }
                fail(manga.title)
            }
        }
        return sent
    }

    /** Envia o progresso em lotes. Devolve quantos capítulos foram enviados. */
    private suspend fun uploadProgress(
        userId: String,
        items: List<Pair<Manga, Chapter>>,
        fail: (String) -> Unit,
    ): Int {
        var sent = 0
        items.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            try {
                uploadProgressBatch(userId, batch)
                sent += batch.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: lote de ${batch.size} capítulos falhou" }
                batch.map { it.first.title }.distinct().forEach(fail)
            }
        }
        return sent
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
                        id = mangaRemoteId(it.source, it.url),
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
                        id = sourceRemoteId(it.source, it.url),
                        mangaId = mangaRemoteId(it.source, it.url),
                        extensionId = it.source.toString(),
                        sourceMangaId = it.url,
                    )
                },
            )
            supabase.postgrest["user_library"].upsert(
                mangas.map {
                    UserLibraryEntrySimple(
                        userId = userId,
                        mangaId = mangaRemoteId(it.source, it.url),
                        status = mapToRemoteStatus(it.readingStatus),
                        isFavorite = it.favorite,
                        sourceId = sourceRemoteId(it.source, it.url),
                        addedAt = now,
                    )
                },
            )
        }
    }

    private suspend fun removeMangasFromCloud(userId: String, keys: List<Pair<Long, String>>) {
        supabase.postgrest["user_library"].delete {
            filter {
                eq("user_id", userId)
                isIn("manga_id", keys.map { (source, url) -> mangaRemoteId(source, url) })
            }
        }
        supabase.postgrest["user_chapter_progress"].delete {
            filter {
                eq("user_id", userId)
                isIn("manga_id", keys.map { (source, url) -> sourceRemoteId(source, url) })
            }
        }
    }

    private suspend fun uploadProgressBatch(userId: String, items: List<Pair<Manga, Chapter>>) {
        val now = Instant.now().toString()
        val remoteChapters = items.map { (manga, chapter) ->
            ChapterUpsertRemote(
                id = chapterRemoteId(manga.source, manga.url, chapter.url),
                mangaSourceId = sourceRemoteId(manga.source, manga.url),
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
                chapterId = chapterRemoteId(manga.source, manga.url, chapter.url),
                mangaId = sourceRemoteId(manga.source, manga.url),
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
            // 23503 (FK): a fonte do manga ainda não existe na cloud. Envia os pais (só favoritos) e repete uma vez
            if (e.statusCode != 409 && e.message?.contains("23503") != true) throw e
            val parents = items.map { it.first }.filter { it.favorite }.distinctBy { it.id }
            if (parents.isEmpty()) throw e
            logcat(LogPriority.WARN) { "Sync: FK 23503 nos capítulos, a enviar primeiro os mangas pai" }
            uploadMangaBatch(userId, parents)
            push()
        }
    }

    /**
     * Import da cloud.
     * - [since] = 0: completo (primeiro sync neste aparelho). Junta a cloud à biblioteca local:
     *   acrescenta o que falta, a cloud ganha no estado de leitura e o progresso nunca recua. Não remove nada.
     * - [since] > 0: incremental, só aplica o que mudou na cloud depois de [since] (ms, `updated_at`).
     *   Não toca no que está por enviar localmente (alterado depois de [pendingSince]).
     *   Os favoritos locais que já não estão na cloud não são removidos: vêm em [SyncResult.localOnly]
     *   para o utilizador decidir.
     */
    suspend fun reconcileCloudToLocal(
        userId: String,
        since: Long,
        pendingSince: Long,
        updateChapter: UpdateChapter,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMangaFailed: (mangaTitle: String) -> Unit = {},
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

        // Pendentes: alterações locais ainda não enviadas (incluindo remoções). Lidas antes de aplicar a cloud
        val pendingMangaIds = if (incremental) {
            (
                mangaRepository.getFavoritesModifiedSince(pendingSince) +
                    mangaRepository.getRemovedFromLibrarySince(pendingSince)
                )
                .map { it.id }
                .toSet()
        } else {
            emptySet()
        }
        val pendingChapterIds = if (incremental) {
            chapterRepository.getFavoriteChaptersModifiedSince(pendingSince).map { it.id }.toSet()
        } else {
            emptySet()
        }
        // Mangas apagados da base de dados cuja remoção ainda não foi enviada: não os recriar
        val deletedKeys = if (incremental) {
            mangaRepository.getDeletedForCloudSince(pendingSince).map { (source, url) ->
                libraryKey(source, url)
            }.toSet()
        } else {
            emptySet()
        }

        // 1. O que mudou na biblioteca da cloud e, para detetar remoções, a lista de tudo o que lá existe
        val remoteItems: List<UserLibraryItem>
        val remoteKeys: Set<String>
        val progressList: List<ChapterProgressRemote>
        val chapterMetadataMap = mutableMapOf<String, ChapterRemote>()
        try {
            remoteItems = fetchRemoteLibrary(userId, updatedSince).map { it.toItem() }
            remoteKeys = if (incremental) {
                fetchRemoteLibraryKeys(userId)
            } else {
                remoteItems.map { libraryKey(it.sourceId, it.mangaUrl) }.toSet()
            }

            // 2. Progresso alterado (paginado de 1000 em 1000) e os metadados dos capítulos respetivos
            progressList = fetchRemoteProgress(userId, updatedSince)
            progressList.map { it.chapterId }.distinct().chunked(UPSERT_BATCH_SIZE).forEach { chunk ->
                val results = withRetry {
                    supabase.postgrest["chapters"]
                        .select { filter { isIn("id", chunk) } }
                        .decodeList<ChapterRemote>()
                }
                chapterMetadataMap.putAll(results.associateBy { it.id })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync: falhou a leitura da cloud" }
            return SyncResult(ok = false)
        }

        var mangasApplied = 0
        var chaptersApplied = 0
        val touchedMangaIds = mutableListOf<Long>()

        // 3. Favoritos: acrescentar os que faltam e atualizar o estado
        remoteItems.forEach { remote ->
            if (libraryKey(remote.sourceId, remote.mangaUrl) in deletedKeys) return@forEach
            try {
                val local = mangaRepository.getMangaByUrlAndSourceId(remote.mangaUrl, remote.sourceId)
                if (local != null && local.id in pendingMangaIds) return@forEach
                restoreRemoteMangaLocally(remote, local)?.let {
                    mangasApplied++
                    touchedMangaIds.add(it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Sync: falhou o import de ${remote.title}" }
                fail(remote.title)
            }
        }
        // O que veio da cloud não é uma alteração local por enviar
        mangaRepository.clearCloudDirty(touchedMangaIds)

        // 4. Favoritos locais que já não estão na cloud: só se avisa, quem decide é o utilizador
        val localOnly = if (incremental) {
            findLocalOnly(mangaRepository.getFavorites(), remoteKeys, pendingMangaIds)
        } else {
            emptyList()
        }

        // 5. Progresso
        val activeMangas = mangaRepository.getFavorites().associateBy { sourceRemoteId(it.source, it.url) }
        val progressByManga = progressList.groupBy { it.mangaId }.filterKeys { it in activeMangas }
        var index = 0
        progressByManga.forEach { (mangaSourceId, entries) ->
            val manga = activeMangas.getValue(mangaSourceId)
            onProgress(++index, progressByManga.size)
            try {
                val localChapters = chapterRepository.getChapterByMangaId(manga.id).associateBy { it.url }
                val updates = entries.mapNotNull { entry ->
                    val metadata = chapterMetadataMap[entry.chapterId] ?: return@mapNotNull null
                    val local = localChapters[metadata.sourceChapterId] ?: return@mapNotNull null
                    if (local.id in pendingChapterIds) return@mapNotNull null
                    val (read, page) = mergeChapterProgress(
                        localRead = local.read,
                        localPage = local.lastPageRead,
                        remoteRead = entry.read,
                        remotePage = entry.lastPageRead.toLong(),
                        incremental = incremental,
                    ) ?: return@mapNotNull null
                    ChapterUpdate(id = local.id, read = read, lastPageRead = page)
                }
                if (updates.isNotEmpty()) {
                    updateChapter.awaitAll(updates)
                    // No import completo o que subiu por causa da junção ainda tem de ir para a cloud
                    if (incremental) chapterRepository.clearCloudDirty(updates.map { it.id })
                    chaptersApplied += updates.size
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Sync: falhou o progresso de ${manga.title}" }
                fail(manga.title)
            }
        }

        val result = SyncResult(
            ok = failedTitles.isEmpty(),
            mangasReceived = remoteItems.size,
            chaptersReceived = progressList.size,
            mangasApplied = mangasApplied,
            chaptersApplied = chaptersApplied,
            localOnly = localOnly,
        )
        logcat(LogPriority.INFO) {
            "Sync: import ${if (incremental) "INCREMENTAL" else "COMPLETO"} | ${result.summary()} | " +
                "soLocais=${localOnly.size} | falhas=${failedTitles.size}"
        }
        return result
    }

    /** @return o id local do manga se foi criado ou alterado, ou null se já estava igual à cloud. */
    private suspend fun restoreRemoteMangaLocally(remote: UserLibraryItem, local: Manga?): Long? {
        val status = mapFromRemoteStatus(remote.status)
        if (local != null) {
            if (local.favorite && local.readingStatus == status) return null
            mangaRepository.update(MangaUpdate(id = local.id, favorite = true, readingStatus = status))
            return local.id
        }
        val newManga = Manga.create().copy(
            url = remote.mangaUrl,
            title = remote.title,
            source = remote.sourceId,
            favorite = true,
            thumbnailUrl = remote.thumbnailUrl,
            readingStatus = status,
            initialized = false,
        )
        return mangaRepository.insertNetworkManga(listOf(newManga)).firstOrNull()?.id
    }

    private fun UserLibraryEntryRemote.toItem() = UserLibraryItem(
        title = manga.title,
        thumbnailUrl = manga.thumbnailUrl,
        status = status,
        sourceId = source.extensionId.toLongOrNull() ?: -1L,
        mangaUrl = source.sourceMangaId,
    )

    private suspend fun fetchRemoteLibrary(userId: String, updatedSince: String?): List<UserLibraryEntryRemote> {
        suspend fun fetch(since: String?): List<UserLibraryEntryRemote> {
            val all = mutableListOf<UserLibraryEntryRemote>()
            var offset = 0
            while (true) {
                val chunk = withRetry {
                    supabase.postgrest["user_library"]
                        .select(columns = LIBRARY_COLUMNS) {
                            filter {
                                eq("user_id", userId)
                                if (since != null) gte("updated_at", since)
                            }
                            order("manga_id", Order.ASCENDING)
                            range(offset.toLong(), (offset + PAGE_SIZE - 1).toLong())
                        }
                        .decodeList<UserLibraryEntryRemote>()
                }
                all.addAll(chunk)
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

    private suspend fun fetchRemoteLibraryKeys(userId: String): Set<String> {
        val keys = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val chunk = withRetry {
                supabase.postgrest["user_library"]
                    .select(columns = Columns.raw("manga_id, manga_sources(extension_id, source_manga_id)")) {
                        filter { eq("user_id", userId) }
                        order("manga_id", Order.ASCENDING)
                        range(offset.toLong(), (offset + PAGE_SIZE - 1).toLong())
                    }
                    .decodeList<LibraryKeyRemote>()
            }
            chunk.mapTo(keys) { "${it.source.extensionId}:${it.source.sourceMangaId}" }
            if (chunk.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return keys
    }

    private suspend fun fetchRemoteProgress(userId: String, updatedSince: String?): List<ChapterProgressRemote> {
        val progressList = mutableListOf<ChapterProgressRemote>()
        var offset = 0
        while (true) {
            val chunk = withRetry {
                supabase.postgrest["user_chapter_progress"]
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
            }
            progressList.addAll(chunk)
            if (chunk.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return progressList
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val UPSERT_BATCH_SIZE = 100
        const val SESSION_MARGIN_MS = 2 * 60 * 1000L

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

/**
 * Resultado de um sync com a cloud. Os contadores servem para o log.
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
    val localOnly: List<Manga> = emptyList(),
) {
    val totalSent: Int get() = mangasSent + chaptersSent + mangasRemoved
    val totalReceived: Int get() = mangasReceived + chaptersReceived

    fun summary(): String =
        "enviados=$totalSent (mangas=$mangasSent, capitulos=$chaptersSent, removidos=$mangasRemoved) | " +
            "recebidos=$totalReceived (mangas=$mangasReceived, capitulos=$chaptersReceived) | " +
            "aplicados localmente=${mangasApplied + chaptersApplied}"
}

internal data class UserLibraryItem(
    val title: String,
    val thumbnailUrl: String?,
    val status: String,
    val sourceId: Long,
    val mangaUrl: String,
)

// --- Modelos remotos (esquema do Supabase, ver supabase/schema.sql) ---

@Serializable
data class UserLibraryEntrySimple(
    @SerialName("user_id") val userId: String,
    @SerialName("manga_id") val mangaId: String,
    val status: String,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("source_id") val sourceId: String,
    @SerialName("added_at") val addedAt: String,
)

@Serializable
data class LibraryKeyRemote(
    @SerialName("manga_id") val mangaId: String,
    @SerialName("manga_sources") val source: SourceKeyRemote,
)

@Serializable
data class SourceKeyRemote(
    @SerialName("extension_id") val extensionId: String,
    @SerialName("source_manga_id") val sourceMangaId: String,
)

@Serializable
data class MangaSourceSyncRemote(
    val id: String,
    @SerialName("manga_id") val mangaId: String,
    @SerialName("extension_id") val extensionId: String,
    @SerialName("source_manga_id") val sourceMangaId: String,
)

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
    @SerialName("chapters") val lastChapter: ChapterRemote? = null,
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
    @SerialName("extensions") val extension: ExtensionRemote,
)

@Serializable
data class ExtensionRemote(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean,
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
    @SerialName("uploaded_at") val uploadedAt: String? = null,
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
    @SerialName("uploaded_at") val uploadedAt: String?,
)

@Serializable
data class ChapterProgressRemote(
    @SerialName("user_id") val userId: String,
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("manga_id") val mangaId: String,
    @SerialName("last_page_read") val lastPageRead: Int,
    val read: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)
