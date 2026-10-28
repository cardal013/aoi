package eu.kanade.tachiyomi.ui.anime.watch

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.data.account.supabase
import eu.kanade.tachiyomi.data.anime.AnimeDownloads
import eu.kanade.tachiyomi.data.anime.AnimeExtensions
import eu.kanade.tachiyomi.data.anime.AnimeLinks
import eu.kanade.tachiyomi.data.anime.AnimeVideos
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.repository.AnimeRepository

/**
 * Ver um anime da lista do MAL: escolher (uma vez) a fonte e o anime certo nela, e depois a lista de episódios
 * com ver e transferir. A escolha fica no aparelho e no Supabase ([AnimeLinks]).
 */
@OptIn(UnstableApi::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AnimeWatchViewModel(
    private val animeRepository: AnimeRepository,
    private val extensions: AnimeExtensions,
    private val links: AnimeLinks,
    val downloads: AnimeDownloads,
) : ViewModel() {

    data class State(
        val anime: Anime? = null,
        val link: AnimeLinks.Link? = null,
        val ready: Boolean = false,
        val choosing: Boolean = false,
        val source: AnimeSource? = null,
        val query: String = "",
        val results: List<SAnime> = emptyList(),
        val searching: Boolean = false,
        val searchError: Boolean = false,
        val episodes: List<SEpisode> = emptyList(),
        val episodesLoading: Boolean = false,
        val episodesError: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val extensionsState = extensions.state
    private var malId = 0L
    private var searchJob: Job? = null

    fun init(malId: Long) {
        if (this.malId == malId) return
        this.malId = malId
        downloads.refresh()
        viewModelScope.launchIO {
            val userId = supabase.auth.currentUserOrNull()?.id
            val anime = userId?.let { animeRepository.getById(it, malId) }
            val link = links.get(malId)
            _state.update {
                it.copy(anime = anime, link = link, ready = true, choosing = link == null, query = anime?.title.orEmpty())
            }
            if (link != null) loadEpisodes() else extensions.sources.firstOrNull()?.let { chooseSource(it) }
        }
        viewModelScope.launch {
            animeRepository.getAllAsFlow(supabase.auth.currentUserOrNull()?.id ?: return@launch).collect { list ->
                list.find { it.malId == malId }?.let { anime -> _state.update { it.copy(anime = anime) } }
            }
        }
    }

    fun linkedSource(): AnimeSource? = _state.value.link?.sourceId?.toLongOrNull()?.let { extensions.source(it) }

    // ---------- Escolher a fonte ----------

    fun startChoosing() {
        _state.update { it.copy(choosing = true, query = it.anime?.title.orEmpty()) }
        (linkedSource() ?: extensions.sources.firstOrNull())?.let { chooseSource(it) }
    }

    fun stopChoosing() {
        if (_state.value.link != null) _state.update { it.copy(choosing = false) }
    }

    fun chooseSource(source: AnimeSource) {
        _state.update { it.copy(source = source) }
        search()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun search() {
        val source = _state.value.source ?: return
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launchIO {
            _state.update { it.copy(searching = true, searchError = false, results = emptyList()) }
            try {
                val page = source.getSearchAnime(1, query, source.getFilterList())
                _state.update { it.copy(results = page.animes, searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anime: pesquisa em ${source.name} falhou" }
                _state.update { it.copy(searching = false, searchError = true) }
            }
        }
    }

    fun pick(anime: SAnime) {
        val source = _state.value.source ?: return
        val link = AnimeLinks.Link(source.id.toString(), anime.url, anime.title)
        _state.update { it.copy(link = link, choosing = false, episodes = emptyList()) }
        viewModelScope.launchIO {
            links.set(malId, link)
            loadEpisodes()
        }
    }

    // ---------- Episódios ----------

    fun loadEpisodes() {
        val link = _state.value.link ?: return
        viewModelScope.launchIO {
            val source = linkedSource()
            if (source == null) {
                _state.update { it.copy(episodesError = true) }
                return@launchIO
            }
            _state.update { it.copy(episodesLoading = true, episodesError = false) }
            try {
                val anime = SAnime.create().apply {
                    url = link.animeUrl
                    title = link.title.orEmpty()
                }
                val episodes = source.getEpisodeList(anime).sortedBy { it.episode_number }
                _state.update { it.copy(episodes = episodes, episodesLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anime: episódios de ${source.name} falharam" }
                _state.update { it.copy(episodesLoading = false, episodesError = true) }
            }
        }
    }

    /** Escolhe o primeiro vídeo (a fonte já os ordena pela preferência) e põe-no a transferir. */
    fun download(episode: SEpisode) {
        val source = linkedSource() ?: return
        val anime = _state.value.anime
        viewModelScope.launchIO {
            try {
                val video = AnimeVideos.load(source, episode).firstOrNull() ?: error("sem vídeos")
                val headers = AnimeVideos.headers(source, video)
                downloads.saveHeaders(video.videoUrl, headers)
                video.subtitleTracks.forEach { downloads.saveHeaders(it.url, headers) }
                val info = AnimeDownloads.Info(malId, anime?.title.orEmpty(), episode.name, episode.episode_number)
                downloads.start(episode.url, AnimeVideos.mediaItem(episode.url, video), info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anime: download de ${episode.name} falhou" }
                _state.update { it.copy(message = "download_failed") }
            }
        }
    }

    fun deleteDownload(episode: SEpisode) = downloads.remove(episode.url)

    fun messageShown() = _state.update { it.copy(message = null) }
}
