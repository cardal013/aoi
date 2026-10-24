package tachiyomi.data.anime

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.repository.AnimeRepository

/** Lista de animes do MAL na base de dados local (tabela mal_anime, ver mal_anime.sq). */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AnimeRepositoryImpl(
    private val database: Database,
) : AnimeRepository {

    override fun getAllAsFlow(userId: String): Flow<List<Anime>> {
        return database.mal_animeQueries.getAll(userId, ::mapAnime).subscribeToList()
    }

    override suspend fun getById(userId: String, malId: Long): Anime? {
        return database.mal_animeQueries.getById(userId, malId, ::mapAnime).awaitAsOneOrNull()
    }

    override suspend fun add(userId: String, anime: Anime) {
        database.mal_animeQueries.add(
            userId = userId,
            malId = anime.malId,
            title = anime.title,
            titleEnglish = anime.titleEnglish,
            coverUrl = anime.coverUrl,
            mediaType = anime.mediaType,
            numEpisodes = anime.numEpisodes,
            startDate = anime.startDate,
            status = anime.status.name,
            now = System.currentTimeMillis(),
        )
    }

    override suspend fun setProgress(userId: String, malId: Long, status: AnimeStatus, episodesWatched: Long) {
        database.mal_animeQueries.setProgress(
            status = status.name,
            episodesWatched = episodesWatched,
            now = System.currentTimeMillis(),
            userId = userId,
            malId = malId,
        )
    }

    override suspend fun remove(userId: String, malId: Long) {
        database.mal_animeQueries.markDeleted(System.currentTimeMillis(), userId, malId)
    }

    override suspend fun getDirty(userId: String): List<Anime> {
        return database.mal_animeQueries.getDirty(userId, ::mapAnime).awaitAsList()
    }

    override suspend fun clearDirty(userId: String, malId: Long, dirtyAt: Long) {
        database.mal_animeQueries.clearDirty(userId, malId, dirtyAt)
    }

    override suspend fun upsertFromCloud(userId: String, animes: List<Anime>) {
        for (anime in animes) {
            database.mal_animeQueries.upsertFromCloud(
                userId = userId,
                malId = anime.malId,
                title = anime.title,
                titleEnglish = anime.titleEnglish,
                coverUrl = anime.coverUrl,
                mediaType = anime.mediaType,
                numEpisodes = anime.numEpisodes,
                startDate = anime.startDate,
                status = anime.status.name,
                episodesWatched = anime.episodesWatched,
                deleted = anime.deleted,
                touchedAt = anime.touchedAt,
                folderId = anime.folderId,
                folderPosition = anime.folderPosition,
                manualPosition = anime.manualPosition,
                manualTab = anime.manualTab?.name,
            )
        }
    }

    override suspend fun setFolder(userId: String, malId: Long, folderId: String?, folderPosition: Double?) {
        database.mal_animeQueries.setFolder(
            folderId = folderId,
            folderPosition = folderPosition,
            now = System.currentTimeMillis(),
            userId = userId,
            malId = malId,
        )
    }

    override suspend fun setFolderPosition(userId: String, malId: Long, folderPosition: Double) {
        database.mal_animeQueries.setFolderPosition(folderPosition, System.currentTimeMillis(), userId, malId)
    }

    override suspend fun setManualPosition(
        userId: String,
        malId: Long,
        manualPosition: Double,
        manualTab: AnimeStatus,
    ) {
        database.mal_animeQueries.setManualPosition(
            manualPosition = manualPosition,
            manualTab = manualTab.name,
            now = System.currentTimeMillis(),
            userId = userId,
            malId = malId,
        )
    }

    override suspend fun getLayoutDirty(userId: String): List<Anime> {
        return database.mal_animeQueries.getLayoutDirty(userId, ::mapAnime).awaitAsList()
    }

    override suspend fun clearLayoutDirty(userId: String, malId: Long, layoutDirtyAt: Long) {
        database.mal_animeQueries.clearLayoutDirty(userId, malId, layoutDirtyAt)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapAnime(
        userId: String,
        malId: Long,
        title: String,
        titleEnglish: String?,
        coverUrl: String?,
        mediaType: String?,
        numEpisodes: Long?,
        startDate: String?,
        status: String,
        episodesWatched: Long,
        deleted: Boolean,
        touchedAt: Long,
        folderId: String?,
        folderPosition: Double?,
        manualPosition: Double?,
        manualTab: String?,
        dirtyAt: Long?,
        layoutDirtyAt: Long?,
    ): Anime = Anime(
        malId = malId,
        title = title,
        titleEnglish = titleEnglish,
        coverUrl = coverUrl,
        mediaType = mediaType,
        numEpisodes = numEpisodes,
        startDate = startDate,
        status = AnimeStatus.fromRemote(status),
        episodesWatched = episodesWatched,
        deleted = deleted,
        touchedAt = touchedAt,
        folderId = folderId,
        folderPosition = folderPosition,
        manualPosition = manualPosition,
        manualTab = manualTab?.let(AnimeStatus::fromRemote),
        dirtyAt = dirtyAt,
        layoutDirtyAt = layoutDirtyAt,
    )
}
