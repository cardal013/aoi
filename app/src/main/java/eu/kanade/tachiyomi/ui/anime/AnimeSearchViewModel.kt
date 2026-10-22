package eu.kanade.tachiyomi.ui.anime

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.anime.AniListAnimeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.repository.AnimeRepository
import kotlin.time.Duration.Companion.milliseconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AnimeSearchViewModel(
    private val api: AniListAnimeApi,
    private val animeRepository: AnimeRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Estado de cada anime que já está na lista, para o mostrar nos resultados. */
    val inList: StateFlow<Map<Long, AnimeStatus>> = animeRepository.getAllAsFlow()
        .map { animes -> animes.associate { it.anilistId to it.status } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyMap())

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launchIO {
            state.map { it.query.trim() }
                .distinctUntilChanged()
                .debounce(SEARCH_DEBOUNCE)
                .collectLatest(::search)
        }
    }

    fun onQueryChange(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun retry() {
        viewModelScope.launchIO { search(state.value.query.trim()) }
    }

    fun add(anime: Anime, status: AnimeStatus) {
        viewModelScope.launchIO { animeRepository.add(anime.copy(status = status)) }
    }

    private suspend fun search(query: String) {
        if (query.length < 2) {
            mutableState.update { it.copy(results = emptyList(), isLoading = false, hasError = false) }
            return
        }
        mutableState.update { it.copy(isLoading = true, hasError = false) }
        try {
            val results = api.search(query)
            mutableState.update { it.copy(results = results, isLoading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "AniList: a pesquisa falhou" }
            mutableState.update { it.copy(isLoading = false, hasError = true) }
        }
    }

    @Immutable
    data class State(
        val query: String = "",
        val results: List<Anime> = emptyList(),
        val isLoading: Boolean = false,
        val hasError: Boolean = false,
    )

    private companion object {
        val SEARCH_DEBOUNCE = 400.milliseconds
    }
}
