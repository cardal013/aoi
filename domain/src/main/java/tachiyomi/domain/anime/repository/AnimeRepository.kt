package tachiyomi.domain.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus

/** Lista de animes do MAL guardada no aparelho, separada por conta do Aoi ([userId]). */
interface AnimeRepository {

    /** A lista sem os removidos, o último mexido primeiro. */
    fun getAllAsFlow(userId: String): Flow<List<Anime>>

    suspend fun getById(userId: String, malId: Long): Anime?

    /** Acrescenta (ou volta a acrescentar) um anime com [Anime.status] e marca-o para enviar. */
    suspend fun add(userId: String, anime: Anime)

    /** Muda o estado e os episódios vistos de uma vez (uma só alteração na fila). */
    suspend fun setProgress(userId: String, malId: Long, status: AnimeStatus, episodesWatched: Long)

    suspend fun remove(userId: String, malId: Long)

    /** Alterações locais (incluindo remoções) que ainda não chegaram à edge function. */
    suspend fun getDirty(userId: String): List<Anime>

    /** Tira a marca de envio, só se não houve outra alteração depois de [dirtyAt]. */
    suspend fun clearDirty(userId: String, malId: Long, dirtyAt: Long)

    /** Aplica o que veio da cloud, sem o marcar para enviar. */
    suspend fun upsertFromCloud(userId: String, animes: List<Anime>)
}
