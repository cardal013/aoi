package tachiyomi.data.anime

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.anime.model.AnimeFolder
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.model.AnimeTabMode
import tachiyomi.domain.anime.model.FolderSortOrder
import tachiyomi.domain.anime.repository.AnimeFolderRepository

/** Pastas de animes e modo de cada aba na base de dados local (anime_folders.sq e anime_tabs.sq). */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AnimeFolderRepositoryImpl(
    private val database: Database,
) : AnimeFolderRepository {

    override fun getAllAsFlow(userId: String): Flow<List<AnimeFolder>> {
        return database.anime_foldersQueries.getAll(userId, ::mapFolder).subscribeToList()
    }

    override suspend fun getAllIncludingDeleted(userId: String): List<AnimeFolder> {
        return database.anime_foldersQueries.getAllIncludingDeleted(userId, ::mapFolder).awaitAsList()
    }

    override suspend fun getDirty(userId: String): List<AnimeFolder> {
        return database.anime_foldersQueries.getDirty(userId, ::mapFolder).awaitAsList()
    }

    override suspend fun create(
        userId: String,
        id: String,
        name: String,
        manualPosition: Double?,
        manualTab: AnimeStatus?,
    ) {
        database.anime_foldersQueries.insert(
            userId = userId,
            id = id,
            name = name,
            manualPosition = manualPosition,
            manualTab = manualTab?.name,
            now = System.currentTimeMillis(),
        )
    }

    override suspend fun rename(userId: String, id: String, name: String) {
        database.anime_foldersQueries.rename(name, System.currentTimeMillis(), userId, id)
    }

    override suspend fun setCover(userId: String, id: String, coverMalId: Long?) {
        database.anime_foldersQueries.setCover(coverMalId, System.currentTimeMillis(), userId, id)
    }

    override suspend fun setSortOrder(userId: String, id: String, sortOrder: FolderSortOrder) {
        database.anime_foldersQueries.setSortOrder(sortOrder.name, System.currentTimeMillis(), userId, id)
    }

    override suspend fun setManualPosition(userId: String, id: String, manualPosition: Double, manualTab: AnimeStatus) {
        database.anime_foldersQueries.setManualPosition(
            manualPosition = manualPosition,
            manualTab = manualTab.name,
            now = System.currentTimeMillis(),
            userId = userId,
            id = id,
        )
    }

    override suspend fun delete(userId: String, id: String) {
        database.anime_foldersQueries.markDeleted(System.currentTimeMillis(), userId, id)
        database.mal_animeQueries.releaseFolder(userId, id)
    }

    override suspend fun deleteRow(userId: String, id: String) {
        database.anime_foldersQueries.deleteRow(userId, id)
    }

    override suspend fun clearDirty(userId: String, id: String, dirtyAt: Long) {
        database.anime_foldersQueries.clearDirty(userId, id, dirtyAt)
    }

    override suspend fun upsertFromCloud(userId: String, folders: List<AnimeFolder>) {
        for (folder in folders) {
            database.anime_foldersQueries.upsertFromCloud(
                userId = userId,
                id = folder.id,
                name = folder.name,
                coverMalId = folder.coverMalId,
                sortOrder = folder.sortOrder.name,
                manualPosition = folder.manualPosition,
                manualTab = folder.manualTab?.name,
            )
        }
    }

    override suspend fun markEmptyFoldersDeleted(userId: String) {
        database.anime_foldersQueries.markEmptyDeleted(System.currentTimeMillis(), userId)
    }

    override suspend fun deleteAll(userId: String) {
        database.anime_foldersQueries.deleteAll(userId)
        database.anime_tabsQueries.deleteAll(userId)
    }

    override fun getTabModesAsFlow(userId: String): Flow<Map<AnimeStatus, AnimeTabMode>> {
        return database.anime_tabsQueries.getAll(userId).subscribeToList().map { rows ->
            rows.mapNotNull { row ->
                AnimeStatus.tabFromRemote(row.tab)?.let { tab -> tab to AnimeTabMode.fromRemote(row.mode) }
            }.toMap()
        }
    }

    override suspend fun setTabMode(userId: String, tab: AnimeStatus, mode: AnimeTabMode) {
        database.anime_tabsQueries.setMode(userId, tab.name, mode.name, System.currentTimeMillis())
    }

    override suspend fun getDirtyTabModes(userId: String): Map<AnimeStatus, Pair<AnimeTabMode, Long>> {
        val dirtyRows = database.anime_tabsQueries.getDirty(userId).awaitAsList()
        val dirtyModes = mutableMapOf<AnimeStatus, Pair<AnimeTabMode, Long>>()
        for (row in dirtyRows) {
            val dirtyAt = row.dirty_at ?: continue
            val tab = AnimeStatus.tabFromRemote(row.tab) ?: continue
            dirtyModes[tab] = AnimeTabMode.fromRemote(row.mode) to dirtyAt
        }
        return dirtyModes
    }

    override suspend fun clearTabModeDirty(userId: String, tab: AnimeStatus, dirtyAt: Long) {
        database.anime_tabsQueries.clearDirty(userId, tab.name, dirtyAt)
    }

    override suspend fun upsertTabModesFromCloud(userId: String, modes: Map<AnimeStatus, AnimeTabMode>) {
        val pendingTabs = getDirtyTabModes(userId).keys
        for ((tab, mode) in modes) {
            if (tab in pendingTabs) {
                continue
            }
            database.anime_tabsQueries.upsertFromCloud(userId, tab.name, mode.name)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapFolder(
        userId: String,
        id: String,
        name: String,
        coverMalId: Long?,
        sortOrder: String,
        manualPosition: Double?,
        manualTab: String?,
        deleted: Boolean,
        dirtyAt: Long?,
    ): AnimeFolder = AnimeFolder(
        id = id,
        name = name,
        coverMalId = coverMalId,
        sortOrder = FolderSortOrder.fromRemote(sortOrder),
        manualPosition = manualPosition,
        manualTab = manualTab?.let(AnimeStatus::tabFromRemote),
        deleted = deleted,
        dirtyAt = dirtyAt,
    )
}
