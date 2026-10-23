package eu.kanade.tachiyomi.ui.anime

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.anime.MalAnimeSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Estado da aba Anime: a lista do MAL da conta com sessão, o estado escolhido e a ligação ao MAL.
 * Cada alteração fica no aparelho e vai logo para o MAL pelo [MalAnimeSync] (ou fica na fila sem rede).
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AnimeListViewModel(
    private val animeRepository: AnimeRepository,
    private val malAnimeSync: MalAnimeSync,
    libraryPreferences: LibraryPreferences,
) : ViewModel() {

    private val lastStatus = libraryPreferences.animeLastStatus
    private var userId: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State> = malAnimeSync.currentUserId
        .flatMapLatest { currentUserId ->
            userId = currentUserId
            val animesFlow = if (currentUserId == null) {
                flowOf(emptyList())
            } else {
                animeRepository.getAllAsFlow(currentUserId)
            }
            combine(
                animesFlow,
                lastStatus.changes(),
                malAnimeSync.connectionFlow(currentUserId),
                malAnimeSync.isSyncing,
            ) { animes, status, connectionAndName, isSyncing ->
                State(
                    animes = animes,
                    status = AnimeStatus.fromRemote(status),
                    connection = connectionAndName.first,
                    malUsername = connectionAndName.second,
                    isSyncing = isSyncing,
                    isLoading = false,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), State())

    fun selectStatus(status: AnimeStatus) {
        lastStatus.set(status.name)
    }

    /** Entrou-se na aba Anime: vai buscar a lista ao MAL. */
    fun onPageOpened() {
        malAnimeSync.requestSync()
    }

    /** Puxar para baixo: vai buscar a lista ao MAL já. */
    fun refresh() {
        malAnimeSync.requestSync(force = true)
    }

    /**
     * Botão "Connect MyAnimeList": pede ao servidor o URL de login e entrega-o a [openUrl]
     * (para abrir no browser). Sem rede ou sem sessão chama [onError].
     */
    fun connect(openUrl: (String) -> Unit, onError: () -> Unit) {
        viewModelScope.launch {
            try {
                openUrl(malAnimeSync.getLoginUrl())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "MAL: não deu para começar o login" }
                onError()
            }
        }
    }

    /** Desliga o MAL da conta; se não der (sem rede) chama [onError]. */
    fun disconnect(onError: () -> Unit) {
        viewModelScope.launch {
            val disconnected = malAnimeSync.disconnect()
            if (!disconnected) {
                onError()
            }
        }
    }

    fun setStatus(anime: Anime, status: AnimeStatus) {
        val currentUserId = userId ?: return
        // Acabar um anime põe os episódios no total, se o MAL o souber
        val finishedWithKnownTotal = status == AnimeStatus.COMPLETED && anime.numEpisodes != null
        val episodesWatched = if (finishedWithKnownTotal) anime.numEpisodes!! else anime.episodesWatched
        viewModelScope.launchIO {
            animeRepository.setProgress(currentUserId, anime.malId, status, episodesWatched)
            malAnimeSync.pushChanges()
        }
    }

    fun setEpisodesWatched(anime: Anime, episodes: Long) {
        val currentUserId = userId ?: return
        val maximumEpisodes = anime.numEpisodes ?: Long.MAX_VALUE
        val episodesWatched = episodes.coerceIn(0, maximumEpisodes)
        viewModelScope.launchIO {
            animeRepository.setProgress(currentUserId, anime.malId, anime.status, episodesWatched)
            malAnimeSync.pushChanges()
        }
    }

    fun remove(anime: Anime) {
        val currentUserId = userId ?: return
        viewModelScope.launchIO {
            animeRepository.remove(currentUserId, anime.malId)
            malAnimeSync.pushChanges()
        }
    }

    @Immutable
    data class State(
        val animes: List<Anime> = emptyList(),
        val status: AnimeStatus = AnimeStatus.WATCHING,
        val connection: MalAnimeSync.Connection = MalAnimeSync.Connection.UNKNOWN,
        val malUsername: String = "",
        val isSyncing: Boolean = false,
        val isLoading: Boolean = true,
    ) {
        fun countFor(status: AnimeStatus): Int = animes.count { it.status == status }
    }
}
