package eu.kanade.tachiyomi.ui.anime

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AnimeListViewModel(
    private val animeRepository: AnimeRepository,
    libraryPreferences: LibraryPreferences,
) : ViewModel() {

    private val lastStatus = libraryPreferences.animeLastStatus

    val state: StateFlow<State> = combine(animeRepository.getAllAsFlow(), lastStatus.changes()) { animes, status ->
        State(animes = animes, status = AnimeStatus.fromRemote(status), isLoading = false)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), State())

    fun selectStatus(status: AnimeStatus) {
        lastStatus.set(status.name)
    }

    fun setStatus(anime: Anime, status: AnimeStatus) {
        viewModelScope.launchIO {
            animeRepository.setStatus(anime.anilistId, status)
            // Acabar um anime põe os episódios no total, se o AniList o souber
            if (status == AnimeStatus.COMPLETED) {
                anime.episodes?.let { animeRepository.setEpisodesWatched(anime.anilistId, it) }
            }
        }
    }

    fun setEpisodesWatched(anime: Anime, episodes: Long) {
        val max = anime.episodes ?: Long.MAX_VALUE
        viewModelScope.launchIO { animeRepository.setEpisodesWatched(anime.anilistId, episodes.coerceIn(0, max)) }
    }

    fun remove(anime: Anime) {
        viewModelScope.launchIO { animeRepository.remove(anime.anilistId) }
    }

    @Immutable
    data class State(
        val animes: List<Anime> = emptyList(),
        val status: AnimeStatus = AnimeStatus.WATCHING,
        val isLoading: Boolean = true,
    ) {
        fun countFor(status: AnimeStatus): Int = animes.count { it.status == status }
    }
}
