package tachiyomi.domain.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus

interface AnimeRepository {

    /** A lista sem os removidos, por título. */
    fun getAllAsFlow(): Flow<List<Anime>>

    suspend fun getById(anilistId: Long): Anime?

    /** Acrescenta (ou volta a acrescentar) um anime com [Anime.status]. */
    suspend fun add(anime: Anime)

    suspend fun setStatus(anilistId: Long, status: AnimeStatus)

    suspend fun setEpisodesWatched(anilistId: Long, episodesWatched: Long)

    suspend fun remove(anilistId: Long)

    /** Alterações locais (incluindo remoções) que ainda não chegaram à cloud. */
    suspend fun getDirty(): List<Anime>

    suspend fun clearDirty(anilistId: Long, dirtyAt: Long)

    /** Aplica o que veio da cloud, sem o marcar para enviar. */
    suspend fun upsertFromCloud(animes: List<Anime>)
}
