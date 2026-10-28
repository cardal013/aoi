package eu.kanade.tachiyomi.data.anime

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.account.supabase
import io.github.jan.supabase.auth.auth
import tachiyomi.domain.anime.AnimeShelf
import tachiyomi.domain.anime.repository.AnimeRepository

/**
 * Ver um episódio até perto do fim conta-o no MAL: os episódios vistos sobem até ao número dele
 * (nunca descem) e o último passa o anime a Completed, como o contador da folha do anime.
 */
@Inject
@SingleIn(AppScope::class)
class AnimeProgress(
    private val animeRepository: AnimeRepository,
    private val malAnimeSync: MalAnimeSync,
) {

    suspend fun markWatched(malId: Long, episodeNumber: Float) {
        val userId = supabase.auth.currentUserOrNull()?.id ?: return
        val anime = animeRepository.getById(userId, malId) ?: return
        val maximum = anime.numEpisodes ?: Long.MAX_VALUE
        val watched = episodeNumber.toLong().coerceIn(0, maximum)
        if (watched <= anime.episodesWatched) return
        animeRepository.setProgress(userId, malId, AnimeShelf.statusForEpisodes(anime, watched), watched)
        malAnimeSync.pushChanges()
    }
}
