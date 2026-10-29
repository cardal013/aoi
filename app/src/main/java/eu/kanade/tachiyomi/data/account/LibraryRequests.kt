package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.ReadingStatus
import tachiyomi.domain.source.service.SourceManager
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pedidos do site para acrescentar mangas à biblioteca (tabela `library_requests`). O browser não corre as
 * extensões, por isso o site só escreve o pedido; a app pesquisa nas fontes instaladas e acrescenta.
 * Um resultado claro entra logo; com vários fica CHOOSE com as opções e o site escolhe (CHOSEN).
 * Fonte que não está instalada neste telemóvel: MISSING_EXTENSION, que volta a ser tratado quando for instalada.
 */
@Inject
@SingleIn(AppScope::class)
class LibraryRequests(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateManga: UpdateManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
) {

    private val table get() = supabase.postgrest[TABLE]

    /**
     * Trata os pedidos à espera. Os mangas acrescentados vão para a cloud no upload a seguir;
     * só depois o [finish] os dá por acrescentados (o manga_id precisa da linha do manga na cloud).
     */
    suspend fun process(userId: String): List<Added> = withIOContext {
        val requests = table
            .select(columns = Columns.list(*REQUEST_COLUMNS)) {
                filter {
                    eq("user_id", userId)
                    isIn("state", listOf(PENDING, CHOSEN, MISSING_EXTENSION, SEARCHING))
                }
                order("created_at", Order.ASCENDING)
                limit(MAX_REQUESTS_PER_SYNC)
            }
            .decodeList<LibraryRequestRow>()

        requests.mapNotNull { request ->
            if (!isReady(request) || !claim(request)) return@mapNotNull null
            try {
                handle(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Pedidos: falhou \"${request.query}\"" }
                update(request.id, FAILED, message = e.message?.take(MAX_MESSAGE) ?: e.javaClass.simpleName)
                null
            }
        }
    }

    /** Dá os pedidos por acrescentados; sem [uploaded] o manga ainda não está na cloud e fica sem manga_id. */
    suspend fun finish(added: List<Added>, uploaded: Boolean) = withIOContext {
        added.forEach {
            val message = if (it.alreadyInLibrary) ALREADY_IN_LIBRARY else null
            try {
                update(it.requestId, ADDED, message = message, mangaId = it.mangaId.takeIf { uploaded })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // O manga pode não ter chegado à cloud (falhou só esse): fica acrescentado sem a ligação
                logcat(LogPriority.WARN, e) { "Pedidos: sem manga_id para \"${it.title}\"" }
                runCatching { update(it.requestId, ADDED, message = message) }
            }
        }
    }

    // Os MISSING_EXTENSION só voltam quando a fonte aparecer; um SEARCHING antigo é de uma vez que a app morreu a meio
    private suspend fun isReady(request: LibraryRequestRow): Boolean = when (request.state) {
        MISSING_EXTENSION -> neededSourceId(request)?.let { sourceManager.get(it) } != null
        SEARCHING -> {
            val updatedAt = OffsetDateTime.parse(request.updatedAt).toInstant().toEpochMilli()
            System.currentTimeMillis() - updatedAt > STALE_SEARCH.inWholeMilliseconds
        }
        else -> true
    }

    private fun neededSourceId(request: LibraryRequestRow): Long? =
        (request.choice?.sourceId ?: request.sourceId)?.toLongOrNull()

    // Com dois telemóveis só um fica com o pedido: o update só passa se o estado ainda for o que foi lido
    private suspend fun claim(request: LibraryRequestRow): Boolean {
        val staleBefore = Instant.now().minusMillis(STALE_SEARCH.inWholeMilliseconds).toString()
        return table.update(buildJsonObject { put("state", SEARCHING) }) {
            select(Columns.list("id"))
            filter {
                eq("id", request.id)
                eq("state", request.state)
                if (request.state == SEARCHING) lt("updated_at", staleBefore)
            }
        }
            .decodeList<RequestIdRow>()
            .isNotEmpty()
    }

    private suspend fun handle(request: LibraryRequestRow): Added? {
        val status = ReadingStatus.entries.find { it.name == request.readingStatus } ?: ReadingStatus.PLAN_TO_READ
        val choice = request.choice
        if (choice != null) {
            val source = choice.sourceId.toLongOrNull()?.let { sourceManager.get(it) }
            if (source == null) {
                update(request.id, MISSING_EXTENSION, message = choice.sourceName)
                return null
            }
            val manga = Manga.create().copy(
                url = choice.url,
                title = choice.title,
                thumbnailUrl = choice.thumbnailUrl,
                source = source.id,
            )
            return add(request, source, manga, status)
        }

        val sources = sourcesFor(request)
        if (sources == null) {
            update(request.id, MISSING_EXTENSION)
            return null
        }
        val results = search(sources, request.query)
        if (results.isEmpty()) {
            update(request.id, NOT_FOUND)
            return null
        }
        val picked = pickRequestCandidate(results.searched, results.found.map { it.candidate })
        if (picked != null) {
            val found = results.found.first { it.candidate == picked }
            return add(request, found.source, found.manga, status)
        }
        val candidates = sortCandidates(results.searched, results.found.map { it.candidate })
        update(request.id, CHOOSE, candidates = candidates)
        return null
    }

    /** As fontes onde procurar, ou null se o site pediu uma que não está instalada. */
    private suspend fun sourcesFor(request: LibraryRequestRow): List<Source>? {
        request.sourceId?.let { id ->
            return id.toLongOrNull()?.let { sourceManager.get(it) }?.let(::listOf)
        }
        val enabledLanguages = sourcePreferences.enabledLanguages.get()
        val disabledSources = sourcePreferences.disabledSources.get()
        val sources = sourceManager.getOnlineSources()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }
        // Um link do site da fonte: só essa fonte
        val host = hostOf(request.query) ?: return sources
        return sources.filter { hostOf(it.baseUrl) == host }.ifEmpty { sources }
    }

    private suspend fun search(sources: List<Source>, query: String): SearchResults {
        // Muitas extensões aceitam o link na pesquisa; se não der, procura pelo nome que vem no link
        val queries = listOfNotNull(query.trim(), titleFromLink(query)).distinct()
        for (text in queries) {
            val found = searchAll(sources, text)
            if (found.isNotEmpty()) return SearchResults(text, found)
        }
        return SearchResults(query, emptyList())
    }

    private suspend fun searchAll(sources: List<Source>, query: String): List<Found> = coroutineScope {
        val semaphore = Semaphore(PARALLEL_SEARCHES)
        sources.map { source ->
            async {
                semaphore.withPermit {
                    withTimeoutOrNull(SEARCH_TIMEOUT) {
                        try {
                            source.getSearchManga(1, query, source.getFilterList()).mangas
                                .take(MAX_PER_SOURCE)
                                .map { it.toDomainManga(source.id) }
                                .distinctBy { it.url }
                                .map { manga ->
                                    Found(
                                        source = source,
                                        manga = manga,
                                        candidate = RequestCandidate(
                                            sourceId = source.id.toString(),
                                            sourceName = source.name,
                                            url = manga.url,
                                            title = manga.title,
                                            thumbnailUrl = manga.thumbnailUrl,
                                        ),
                                    )
                                }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Pedidos: pesquisa falhou em ${source.name}" }
                            emptyList()
                        }
                    }.orEmpty()
                }
            }
        }
            .awaitAll()
            .flatten()
    }

    private suspend fun add(request: LibraryRequestRow, source: Source, remote: Manga, status: ReadingStatus): Added {
        val manga = networkToLocalManga(remote)
        val added = Added(
            requestId = request.id,
            mangaId = mangaRemoteId(manga.source, manga.url),
            title = manga.title,
            alreadyInLibrary = manga.favorite,
        )
        if (manga.favorite) return added

        // Detalhes e capítulos já, para o site os mostrar (como ao abrir o manga na app); sem rede fica para depois
        updateMangaFromRemote(source = source, manga = manga, fetchDetails = true, fetchChapters = true)
        setMangaDefaultChapterFlags.await(manga)
        val defaultCategory = libraryPreferences.defaultCategory.get().toLong()
        if (getCategories.await().any { it.id == defaultCategory && !it.isSystemCategory }) {
            setMangaCategories.await(manga.id, listOf(defaultCategory))
        }
        updateManga.await(
            MangaUpdate(
                id = manga.id,
                favorite = true,
                dateAdded = Clock.System.now().toEpochMilliseconds(),
                readingStatus = status,
            ),
        )
        return added
    }

    private suspend fun update(
        id: String,
        state: String,
        message: String? = null,
        mangaId: String? = null,
        candidates: List<RequestCandidate>? = null,
    ) {
        val body = buildJsonObject {
            put("state", state)
            put("message", message)
            if (mangaId != null) put("manga_id", mangaId)
            if (candidates != null) put("candidates", Json.encodeToJsonElement(candidates))
        }
        table.update(body) { filter { eq("id", id) } }
    }

    data class Added(
        val requestId: String,
        val mangaId: String,
        val title: String,
        val alreadyInLibrary: Boolean,
    )

    private class Found(val source: Source, val manga: Manga, val candidate: RequestCandidate)

    private class SearchResults(val searched: String, val found: List<Found>) {
        fun isEmpty() = found.isEmpty()
    }

    private companion object {
        const val TABLE = "library_requests"
        val REQUEST_COLUMNS = arrayOf("id", "query", "source_id", "reading_status", "state", "choice", "updated_at")

        const val PENDING = "PENDING"
        const val SEARCHING = "SEARCHING"
        const val CHOOSE = "CHOOSE"
        const val CHOSEN = "CHOSEN"
        const val ADDED = "ADDED"
        const val NOT_FOUND = "NOT_FOUND"
        const val MISSING_EXTENSION = "MISSING_EXTENSION"
        const val FAILED = "FAILED"

        const val ALREADY_IN_LIBRARY = "Already in your library"
        const val MAX_REQUESTS_PER_SYNC = 20L
        const val MAX_PER_SOURCE = 6
        const val MAX_MESSAGE = 300
        const val PARALLEL_SEARCHES = 5
        val SEARCH_TIMEOUT = 20.seconds
        val STALE_SEARCH = 10.minutes
    }
}

@Serializable
data class LibraryRequestRow(
    val id: String,
    val query: String,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("reading_status") val readingStatus: String,
    val state: String,
    val choice: RequestCandidate? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class RequestCandidate(
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_name") val sourceName: String,
    val url: String,
    val title: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

@Serializable
data class RequestIdRow(val id: String)

/** Título sem maiúsculas, espaços nem pontuação, para comparar o pedido com os resultados. */
internal fun normalizeTitle(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

/**
 * O resultado a acrescentar sem perguntar: o único que veio, ou o único com o título igual ao pedido.
 * Null = o site escolhe (o mesmo título em duas fontes também pergunta, para escolher a fonte).
 */
internal fun pickRequestCandidate(query: String, candidates: List<RequestCandidate>): RequestCandidate? {
    candidates.singleOrNull()?.let { return it }
    val wanted = normalizeTitle(query)
    return candidates.filter { normalizeTitle(it.title) == wanted }.singleOrNull()
}

/** As opções para o site: primeiro as de título igual ao pedido, depois pela ordem das fontes. */
internal fun sortCandidates(query: String, candidates: List<RequestCandidate>): List<RequestCandidate> {
    val wanted = normalizeTitle(query)
    return candidates
        .sortedBy { normalizeTitle(it.title) != wanted }
        .take(MAX_CANDIDATES)
}

private const val MAX_CANDIDATES = 40

internal fun hostOf(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
    return runCatching { URI(trimmed).host }.getOrNull()?.lowercase()?.removePrefix("www.")
}

/** "https://site/manga/solo-leveling-123/" → "solo leveling" (o nome que costuma vir no fim do link). */
internal fun titleFromLink(text: String): String? {
    if (hostOf(text) == null) return null
    val path = runCatching { URI(text.trim()).path }.getOrNull() ?: return null
    val slug = path.trimEnd('/').substringAfterLast('/')
    val words = slug.split('-', '_', '+', ' ')
        .filter { it.isNotBlank() && !it.all(Char::isDigit) }
    return words.joinToString(" ").takeIf { it.length >= 2 }
}
