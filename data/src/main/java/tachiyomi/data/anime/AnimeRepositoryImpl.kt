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

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AnimeRepositoryImpl(
    private val database: Database,
) : AnimeRepository {

    override fun getAllAsFlow(): Flow<List<Anime>> {
        return database.animeQueries.getAll(::mapAnime).subscribeToList()
    }

    override suspend fun getById(anilistId: Long): Anime? {
        return database.animeQueries.getById(anilistId, ::mapAnime).awaitAsOneOrNull()
    }

    override suspend fun add(anime: Anime) {
        database.animeQueries.add(
            anilistId = anime.anilistId,
            title = anime.title,
            titleEnglish = anime.titleEnglish,
            coverUrl = anime.coverUrl,
            format = anime.format,
            episodes = anime.episodes,
            seasonYear = anime.seasonYear,
            status = anime.status.name,
            now = System.currentTimeMillis(),
        )
    }

    override suspend fun setStatus(anilistId: Long, status: AnimeStatus) {
        database.animeQueries.setStatus(status.name, System.currentTimeMillis(), anilistId)
    }

    override suspend fun setEpisodesWatched(anilistId: Long, episodesWatched: Long) {
        database.animeQueries.setEpisodesWatched(episodesWatched, System.currentTimeMillis(), anilistId)
    }

    override suspend fun remove(anilistId: Long) {
        database.animeQueries.markDeleted(System.currentTimeMillis(), anilistId)
    }

    override suspend fun getDirty(): List<Anime> {
        return database.animeQueries.getDirty(::mapAnime).awaitAsList()
    }

    override suspend fun clearDirty(anilistId: Long, dirtyAt: Long) {
        database.animeQueries.clearDirty(anilistId, dirtyAt)
    }

    override suspend fun upsertFromCloud(animes: List<Anime>) {
        animes.forEach { anime ->
            database.animeQueries.upsertFromCloud(
                anilistId = anime.anilistId,
                title = anime.title,
                titleEnglish = anime.titleEnglish,
                coverUrl = anime.coverUrl,
                format = anime.format,
                episodes = anime.episodes,
                seasonYear = anime.seasonYear,
                status = anime.status.name,
                episodesWatched = anime.episodesWatched,
                deleted = anime.deleted,
                addedAt = anime.addedAt,
            )
        }
    }

    private fun mapAnime(
        anilistId: Long,
        title: String,
        titleEnglish: String?,
        coverUrl: String?,
        format: String?,
        episodes: Long?,
        seasonYear: Long?,
        status: String,
        episodesWatched: Long,
        deleted: Boolean,
        addedAt: Long,
        dirtyAt: Long?,
    ): Anime = Anime(
        anilistId = anilistId,
        title = title,
        titleEnglish = titleEnglish,
        coverUrl = coverUrl,
        format = format,
        episodes = episodes,
        seasonYear = seasonYear,
        status = AnimeStatus.fromRemote(status),
        episodesWatched = episodesWatched,
        deleted = deleted,
        addedAt = addedAt,
        dirtyAt = dirtyAt,
    )
}
