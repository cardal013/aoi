package eu.kanade.tachiyomi.data.account

import tachiyomi.domain.manga.model.Manga
import java.util.UUID

// IDs remotos determinísticos (UUID v3): o mesmo manga tem o mesmo ID em todos os aparelhos.
// Mudar a fórmula desliga tudo o que já está na cloud (há um teste para isso).

internal fun mangaRemoteId(source: Long, url: String): String =
    UUID.nameUUIDFromBytes("manga:$source:$url".toByteArray()).toString()

// É este o manga_id de user_chapter_progress (não o de mangaRemoteId)
internal fun sourceRemoteId(source: Long, url: String): String =
    UUID.nameUUIDFromBytes("source:$source:$url".toByteArray()).toString()

internal fun chapterRemoteId(source: Long, mangaUrl: String, chapterUrl: String): String =
    UUID.nameUUIDFromBytes("chapter:$source:$mangaUrl:$chapterUrl".toByteArray()).toString()

internal fun libraryKey(source: Long, url: String): String = "$source:$url"

/**
 * Estado (lido, página) que o capítulo local fica a ter depois de ler o progresso da cloud, ou null se não muda.
 * No import completo (primeiro sync neste aparelho) junta os dois sem nunca recuar.
 * No incremental a entrada da cloud é mais recente e ganha (pode ser um "marcar como não lido" noutro aparelho).
 */
internal fun mergeChapterProgress(
    localRead: Boolean,
    localPage: Long,
    remoteRead: Boolean,
    remotePage: Long,
    incremental: Boolean,
): Pair<Boolean, Long>? {
    val read = if (incremental) remoteRead else localRead || remoteRead
    val page = if (incremental) remotePage else maxOf(localPage, remotePage)
    return if (read == localRead && page == localPage) null else read to page
}

/** Favoritos locais que já não estão na cloud e não têm alterações por enviar. */
internal fun findLocalOnly(
    localFavorites: List<Manga>,
    remoteKeys: Set<String>,
    pendingIds: Set<Long>,
): List<Manga> = localFavorites.filter { libraryKey(it.source, it.url) !in remoteKeys && it.id !in pendingIds }
