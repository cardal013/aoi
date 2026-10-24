package eu.kanade.tachiyomi.ui.anime

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.anime.MalAnimeApi
import eu.kanade.tachiyomi.data.anime.MalAnimeSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val api: MalAnimeApi,
    private val animeRepository: AnimeRepository,
    private val malAnimeSync: MalAnimeSync,
) : ViewModel() {

    private var userId: String? = null

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Estado de cada anime que já está na lista, para o mostrar nos resultados. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val inList: StateFlow<Map<Long, AnimeStatus>> = malAnimeSync.currentUserId
        .flatMapLatest { currentUserId ->
            userId = currentUserId
            if (currentUserId == null) flowOf(emptyList()) else animeRepository.getAllAsFlow(currentUserId)
        }
        .map { animes -> animes.associate { it.malId to it.status } }
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

    /**
     * Acrescenta [animes] no estado [status] (os que já estão na lista mudam para esse estado).
     * Com [folderId] (pesquisa aberta dentro de uma pasta) ficam nessa pasta, no fim da ordem à mão dela;
     * um que estava noutra pasta ou solto passa para esta.
     */
    fun add(animes: List<Anime>, status: AnimeStatus, folderId: String?) {
        val currentUserId = userId ?: return
        viewModelScope.launchIO {
            for (anime in animes) {
                animeRepository.add(currentUserId, anime.copy(status = status))
            }
            if (folderId != null) {
                putInFolder(currentUserId, animes, folderId)
            }
            malAnimeSync.pushChanges()
        }
    }

    private suspend fun putInFolder(currentUserId: String, animes: List<Anime>, folderId: String) {
        val listAnimes = animeRepository.getAllAsFlow(currentUserId).first()
        val alreadyInFolder = listAnimes.filter { it.folderId == folderId }.map { it.malId }.toSet()
        var lastPosition = listAnimes.filter { it.folderId == folderId }.mapNotNull { it.folderPosition }.maxOrNull()
        for (anime in animes) {
            if (anime.malId in alreadyInFolder) {
                continue
            }
            val newPosition = if (lastPosition == null) FIRST_FOLDER_POSITION else lastPosition + 1
            animeRepository.setFolder(currentUserId, anime.malId, folderId, newPosition)
            lastPosition = newPosition
        }
    }

    private suspend fun search(query: String) {
        if (query.length < MIN_QUERY_LENGTH) {
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
            logcat(LogPriority.WARN, e) { "MAL: a pesquisa falhou" }
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

    companion object {
        val SEARCH_DEBOUNCE = 400.milliseconds

        // Mínimo de letras para pesquisar (o mesmo da versão do AniList; o do MAL está por confirmar)
        const val MIN_QUERY_LENGTH = 2

        private const val FIRST_FOLDER_POSITION = 0.0
    }
}
