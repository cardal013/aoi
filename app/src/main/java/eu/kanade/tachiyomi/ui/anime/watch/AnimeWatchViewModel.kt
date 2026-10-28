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
import eu.kanade.tachiyomi.data.anime.AnimeMatch
import eu.kanade.tachiyomi.data.anime.AnimeVideos
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.repository.AnimeRepository

/**
 * Ver um anime da lista do MAL. Da primeira vez procura o anime em todas as fontes instaladas e, se um
 * resultado bater certo com o título do MAL, fica logo com ele; senão mostra os resultados para escolher.
 * A escolha fica no aparelho e no Supabase ([AnimeLinks]).
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

    /** Um resultado da pesquisa, com a fonte de onde veio. */
    data class Result(val source: AnimeSource, val anime: SAnime, val score: Double)

    data class State(
        val anime: Anime? = null,
        val link: AnimeLinks.Link? = null,
        val ready: Boolean = false,
        val choosing: Boolean = false,
        // null = todas as fontes
        val source: AnimeSource? = null,
        val query: String = "",
        val results: List<Result> = emptyList(),
        val searching: Boolean = false,
        val searchError: Boolean = false,
        val episodes: List<SEpisode> = emptyList(),
        val episodesLoading: Boolean = false,
        val episodesError: Boolean = false,
        // a fonte ligada fechou a app da última vez: os episódios só carregam se se pedir
        val sourceCrashed: Boolean = false,
        val message: String? = null,
    ) {
        /** O episódio a seguir ao último visto (ou o primeiro). */
        val nextEpisode: SEpisode?
            get() {
                val watched = anime?.episodesWatched ?: 0
                return episodes.firstOrNull { it.episode_number > watched } ?: episodes.firstOrNull()
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val extensionsState = extensions.state
    private var malId = 0L
    private var searchJob: Job? = null
    private var autoSearched = false

    /** O player abre sozinho uma vez por cada vez que se entra no ecrã. */
    var autoPlayed = false

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
            if (link != null) loadEpisodes()
            // As extensões carregam à parte (no arranque, ou quando uma acaba de instalar): quando houver fontes,
            // procura o anime se ainda não está ligado, ou volta a tentar os episódios
            extensions.state.map { st -> st.installed.isNotEmpty() }.distinctUntilChanged().collect { hasSources ->
                val st = _state.value
                when {
                    !hasSources -> Unit
                    st.link == null && !autoSearched -> {
                        autoSearched = true
                        search(auto = true)
                    }
                    st.link != null && st.episodesError -> loadEpisodes()
                }
            }
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
        _state.update { it.copy(choosing = true, source = null, query = it.anime?.title.orEmpty()) }
        search()
    }

    fun stopChoosing() {
        if (_state.value.link != null) _state.update { it.copy(choosing = false) }
    }

    fun chooseSource(source: AnimeSource?) {
        _state.update { it.copy(source = source) }
        search()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    /**
     * Pesquisa na fonte escolhida ou em todas. Com [auto], pesquisa com o título e o título em inglês e,
     * se o melhor resultado bater certo, liga-o logo e mostra os episódios.
     */
    fun search(auto: Boolean = false) {
        val st = _state.value
        val titles = listOfNotNull(st.anime?.title, st.anime?.titleEnglish).filter { it.isNotBlank() }.distinct()
        val queries = if (auto) titles else listOf(st.query.trim()).filter { it.isNotEmpty() }
        // Em "All" ficam de fora as que fecharam a app; escolhida à mão, usa-se na mesma
        val sources = st.source?.let { listOf(it) } ?: extensions.sources.filterNot { extensions.isCrashed(it) }
        if (queries.isEmpty() || sources.isEmpty()) return
        val scoreTitles = (titles + queries).distinct()
        searchJob?.cancel()
        searchJob = viewModelScope.launchIO {
            _state.update { it.copy(searching = true, searchError = false, results = emptyList()) }
            val limit = Semaphore(4)
            val found = coroutineScope {
                sources.flatMap { source -> queries.map { query -> source to query } }.map { (source, query) ->
                    async {
                        limit.withPermit {
                            withTimeoutOrNull(SEARCH_TIMEOUT) { searchOne(source, query, scoreTitles) }
                        }
                    }
                }.awaitAll()
            }
            val ok = found.filterNotNull()
            val results = ok.flatten()
                .distinctBy { it.source.id to it.anime.url }
                .sortedByDescending { it.score }
            _state.update { it.copy(results = results, searching = false, searchError = ok.isEmpty()) }
            val best = results.firstOrNull()
            if (auto && best != null && best.score >= AnimeMatch.AUTO_PICK) pick(best)
        }
    }

    private suspend fun searchOne(source: AnimeSource, query: String, titles: List<String>): List<Result>? {
        return try {
            extensions.guard(source) {
                source.getSearchAnime(1, query, source.getFilterList()).animes
                    .map { Result(source, it, AnimeMatch.score(titles, it.title)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: uma extensão feita para outra versão da API dá NoSuchMethodError e afins, que não são Exception
            logcat(LogPriority.WARN, e) { "Anime: pesquisa em ${source.name} falhou" }
            null
        }
    }

    fun pick(result: Result) {
        val link = AnimeLinks.Link(result.source.id.toString(), result.anime.url, result.anime.title)
        _state.update { it.copy(link = link, choosing = false, episodes = emptyList()) }
        viewModelScope.launchIO {
            links.set(malId, link)
            loadEpisodes()
        }
    }

    // ---------- Episódios ----------

    /** Com [force] carrega mesmo que a fonte tenha fechado a app da última vez (botão "Try anyway"). */
    fun loadEpisodes(force: Boolean = false) {
        val link = _state.value.link ?: return
        viewModelScope.launchIO {
            val source = linkedSource()
            if (source == null) {
                _state.update { it.copy(episodesError = true) }
                return@launchIO
            }
            if (!force && extensions.isCrashed(source)) {
                _state.update { it.copy(sourceCrashed = true) }
                return@launchIO
            }
            _state.update { it.copy(episodesLoading = true, episodesError = false, sourceCrashed = false) }
            try {
                val anime = SAnime.create().apply {
                    url = link.animeUrl
                    title = link.title.orEmpty()
                }
                val episodes = extensions.guard(source) { source.getEpisodeList(anime) }.sortedBy { it.episode_number }
                _state.update { it.copy(episodes = episodes, episodesLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
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
                val (video, headers) = extensions.guard(source) {
                    val video = AnimeVideos.load(source, episode).firstOrNull() ?: error("sem vídeos")
                    video to AnimeVideos.headers(source, video)
                }
                downloads.saveHeaders(video.videoUrl, headers)
                video.subtitleTracks.forEach { downloads.saveHeaders(it.url, headers) }
                val info = AnimeDownloads.Info(malId, anime?.title.orEmpty(), episode.name, episode.episode_number)
                downloads.start(episode.url, AnimeVideos.mediaItem(episode.url, video), info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Anime: download de ${episode.name} falhou" }
                _state.update { it.copy(message = "download_failed") }
            }
        }
    }

    fun deleteDownload(episode: SEpisode) = downloads.remove(episode.url)

    fun messageShown() = _state.update { it.copy(message = null) }

    private companion object {
        const val SEARCH_TIMEOUT = 20_000L
    }
}
