package tachiyomi.domain.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.AnimeFolder
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.model.AnimeTabMode
import tachiyomi.domain.anime.model.FolderSortOrder

/**
 * Pastas de animes e modo de ordem de cada aba, guardados no aparelho por conta do Aoi ([userId]).
 * Cada alteração fica marcada (dirtyAt) até chegar ao Supabase.
 */
interface AnimeFolderRepository {

    /** Pastas que não estão apagadas, por nome. */
    fun getAllAsFlow(userId: String): Flow<List<AnimeFolder>>

    /** Todas as pastas, incluindo as apagadas aqui que ainda não chegaram ao Supabase. */
    suspend fun getAllIncludingDeleted(userId: String): List<AnimeFolder>

    suspend fun getDirty(userId: String): List<AnimeFolder>

    /** Cria uma pasta vazia (capa da primeira temporada, ordem por data crescente). */
    suspend fun create(userId: String, id: String, name: String, manualPosition: Double?, manualTab: AnimeStatus?)

    suspend fun rename(userId: String, id: String, name: String)

    /** Capa escolhida ([coverMalId]); null = primeira temporada. */
    suspend fun setCover(userId: String, id: String, coverMalId: Long?)

    suspend fun setSortOrder(userId: String, id: String, sortOrder: FolderSortOrder)

    suspend fun setManualPosition(userId: String, id: String, manualPosition: Double, manualTab: AnimeStatus)

    /** Apaga a pasta: os animes ficam soltos; a pasta fica marcada até a remoção chegar ao Supabase. */
    suspend fun delete(userId: String, id: String)

    /** Tira a pasta da tabela (remoção já enviada, ou apagada noutro aparelho). */
    suspend fun deleteRow(userId: String, id: String)

    suspend fun clearDirty(userId: String, id: String, dirtyAt: Long)

    suspend fun upsertFromCloud(userId: String, folders: List<AnimeFolder>)

    /** Modo de cada aba (as que não têm linha são RECENT). */
    fun getTabModesAsFlow(userId: String): Flow<Map<AnimeStatus, AnimeTabMode>>

    suspend fun setTabMode(userId: String, tab: AnimeStatus, mode: AnimeTabMode)

    /** Abas com o modo por enviar: aba -> (modo, dirtyAt). */
    suspend fun getDirtyTabModes(userId: String): Map<AnimeStatus, Pair<AnimeTabMode, Long>>

    suspend fun clearTabModeDirty(userId: String, tab: AnimeStatus, dirtyAt: Long)

    /** Aplica os modos que vieram da cloud, sem os marcar para enviar (não mexe nos que estão por enviar). */
    suspend fun upsertTabModesFromCloud(userId: String, modes: Map<AnimeStatus, AnimeTabMode>)
}
