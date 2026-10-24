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
import kotlinx.coroutines.flow.Flow
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
import tachiyomi.domain.anime.AnimeShelf
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeFolder
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.model.AnimeTabMode
import tachiyomi.domain.anime.model.FolderSortOrder
import tachiyomi.domain.anime.repository.AnimeFolderRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.UUID

/**
 * Estado da aba Anime e do ecrã de uma pasta: a lista do MAL da conta com sessão, as pastas, o modo de
 * cada aba (Recentes/Manual), o estado escolhido e a ligação ao MAL.
 * Cada alteração fica no aparelho e vai logo para o MAL ou para o Supabase pelo [MalAnimeSync]
 * (ou fica na fila sem rede). As regras das pastas e da ordem estão em [AnimeShelf].
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AnimeListViewModel(
    private val animeRepository: AnimeRepository,
    private val folderRepository: AnimeFolderRepository,
    private val malAnimeSync: MalAnimeSync,
    libraryPreferences: LibraryPreferences,
) : ViewModel() {

    private val lastStatus = libraryPreferences.animeLastStatus
    private var userId: String? = null

    /** Animes, pastas e modos das abas da conta, juntos. */
    private data class Shelf(
        val animes: List<Anime>,
        val folders: List<AnimeFolder>,
        val tabModes: Map<AnimeStatus, AnimeTabMode>,
    )

    private fun shelfFlow(currentUserId: String?): Flow<Shelf> {
        if (currentUserId == null) {
            return flowOf(Shelf(emptyList(), emptyList(), emptyMap()))
        }
        return combine(
            animeRepository.getAllAsFlow(currentUserId),
            folderRepository.getAllAsFlow(currentUserId),
            folderRepository.getTabModesAsFlow(currentUserId),
        ) { animes, folders, tabModes -> Shelf(animes, folders, tabModes) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State> = malAnimeSync.currentUserId
        .flatMapLatest { currentUserId ->
            userId = currentUserId
            combine(
                shelfFlow(currentUserId),
                lastStatus.changes(),
                malAnimeSync.connectionFlow(currentUserId),
                malAnimeSync.isSyncing,
            ) { shelf, status, connectionAndName, isSyncing ->
                State(
                    animes = shelf.animes,
                    folders = shelf.folders,
                    tabModes = shelf.tabModes,
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

    /**
     * Corre [change] com a conta com sessão e depois manda a fila.
     * Sem sessão não faz nada (a aba nem mostra a lista).
     */
    private fun changeAndPush(change: suspend (userId: String) -> Unit) {
        val currentUserId = userId ?: return
        viewModelScope.launchIO {
            change(currentUserId)
            malAnimeSync.pushChanges()
        }
    }

    // ---------- Estado e episódios (vão para o MAL) ----------

    fun setStatus(anime: Anime, status: AnimeStatus) {
        // Acabar um anime põe os episódios no total, se o MAL o souber
        val finishedWithKnownTotal = status == AnimeStatus.COMPLETED && anime.numEpisodes != null
        val episodesWatched = if (finishedWithKnownTotal) anime.numEpisodes!! else anime.episodesWatched
        changeAndPush { currentUserId ->
            animeRepository.setProgress(currentUserId, anime.malId, status, episodesWatched)
        }
    }

    /** Chegar ao último episódio marca a temporada como vista (a pasta só passa a Completed com todas vistas). */
    fun setEpisodesWatched(anime: Anime, episodes: Long) {
        val maximumEpisodes = anime.numEpisodes ?: Long.MAX_VALUE
        val episodesWatched = episodes.coerceIn(0, maximumEpisodes)
        val status = AnimeShelf.statusForEpisodes(anime, episodesWatched)
        changeAndPush { currentUserId ->
            animeRepository.setProgress(currentUserId, anime.malId, status, episodesWatched)
        }
    }

    fun remove(anime: Anime) {
        changeAndPush { currentUserId -> animeRepository.remove(currentUserId, anime.malId) }
    }

    // ---------- Ordem das abas (vai para o Supabase) ----------

    /** Botão Recentes/Manual da aba [tab]. */
    fun setTabMode(tab: AnimeStatus, mode: AnimeTabMode) {
        changeAndPush { currentUserId -> folderRepository.setTabMode(currentUserId, tab, mode) }
    }

    /**
     * Arrastou-se [movedKey] na aba [tab] e a ordem ficou [orderedKeys]. Grava as posições à mão
     * (só as que mudam) e, se a aba estava em Recentes, passa-a a Manual.
     */
    fun onTabReordered(tab: AnimeStatus, orderedKeys: List<String>, movedKey: String) {
        val currentState = state.value
        val itemsByKey = currentState.itemsFor(tab).associateBy { it.key }
        val orderedItems = orderedKeys.mapNotNull { itemsByKey[it] }
        val newPositions = AnimeShelf.positionsAfterDrag(orderedItems, movedKey, tab)
        val wasRecent = currentState.modeFor(tab) == AnimeTabMode.RECENT

        changeAndPush { currentUserId ->
            for ((key, position) in newPositions) {
                when (val item = itemsByKey[key]) {
                    is AnimeShelf.LooseAnime ->
                        animeRepository.setManualPosition(currentUserId, item.anime.malId, position, tab)
                    is AnimeShelf.Folder ->
                        folderRepository.setManualPosition(currentUserId, item.folder.id, position, tab)
                    null -> Unit
                }
            }
            if (wasRecent) {
                folderRepository.setTabMode(currentUserId, tab, AnimeTabMode.MANUAL)
            }
        }
    }

    // ---------- Pastas (vão para o Supabase) ----------

    /**
     * Cria a pasta [name] com [anime] lá dentro. A pasta fica no lugar do anime na ordem à mão
     * (se ele estava arrumado à mão nesta aba).
     */
    fun createFolder(name: String, anime: Anime) {
        val folderId = UUID.randomUUID().toString()
        val wasArrangedInItsTab = anime.manualTab == anime.status && anime.manualPosition != null
        val manualPosition = if (wasArrangedInItsTab) anime.manualPosition else null
        val manualTab = if (wasArrangedInItsTab) anime.manualTab else null
        changeAndPush { currentUserId ->
            // Primeiro o anime e só depois a pasta: assim a pasta nunca existe vazia (e uma pasta vazia
            // apaga-se sozinha no envio). Até a pasta existir, o anime aparece solto
            animeRepository.setFolder(currentUserId, anime.malId, folderId, FIRST_FOLDER_POSITION)
            folderRepository.create(currentUserId, folderId, name.trim(), manualPosition, manualTab)
        }
    }

    /** Põe [anime] na pasta [folderId], no fim da ordem à mão dela (um anime só está numa pasta). */
    fun moveToFolder(anime: Anime, folderId: String) {
        val folder = state.value.folderById(folderId) ?: return
        val lastPosition = folder.members.mapNotNull { it.folderPosition }.maxOrNull()
        val newPosition = if (lastPosition == null) FIRST_FOLDER_POSITION else lastPosition + 1
        changeAndPush { currentUserId ->
            animeRepository.setFolder(currentUserId, anime.malId, folderId, newPosition)
        }
    }

    /** Tira [anime] da pasta: fica solto e vai para o topo da aba no modo Manual. Se a pasta ficar vazia, apaga-se. */
    fun removeFromFolder(anime: Anime) {
        changeAndPush { currentUserId -> animeRepository.setFolder(currentUserId, anime.malId, null, null) }
    }

    fun renameFolder(folderId: String, name: String) {
        changeAndPush { currentUserId -> folderRepository.rename(currentUserId, folderId, name.trim()) }
    }

    /** Capa da pasta: a do anime [malId]. */
    fun setFolderCover(folderId: String, malId: Long) {
        changeAndPush { currentUserId -> folderRepository.setCover(currentUserId, folderId, malId) }
    }

    /**
     * Botão de ordem da pasta: data crescente <-> decrescente; se a pasta estava à mão, volta à data
     * (crescente, a ordem por defeito).
     */
    fun toggleFolderSortOrder(folder: AnimeFolder) {
        val nextOrder = when (folder.sortOrder) {
            FolderSortOrder.DATE_ASC -> FolderSortOrder.DATE_DESC
            FolderSortOrder.DATE_DESC -> FolderSortOrder.DATE_ASC
            FolderSortOrder.MANUAL -> FolderSortOrder.DATE_ASC
        }
        changeAndPush { currentUserId -> folderRepository.setSortOrder(currentUserId, folder.id, nextOrder) }
    }

    /** Arrastou-se dentro da pasta: a ordem passa a ser à mão, [orderedMalIds] de cima para baixo. */
    fun onFolderReordered(folderId: String, orderedMalIds: List<Long>) {
        val folder = state.value.folderById(folderId) ?: return
        val membersById = folder.members.associateBy { it.malId }
        val orderedMembers = orderedMalIds.mapNotNull { membersById[it] }
        val newPositions = AnimeShelf.folderPositionsAfterDrag(orderedMembers)
        changeAndPush { currentUserId ->
            for ((malId, position) in newPositions) {
                animeRepository.setFolderPosition(currentUserId, malId, position)
            }
            folderRepository.setSortOrder(currentUserId, folderId, FolderSortOrder.MANUAL)
        }
    }

    /** Apaga a pasta: os animes ficam soltos. */
    fun deleteFolder(folderId: String) {
        changeAndPush { currentUserId -> folderRepository.delete(currentUserId, folderId) }
    }

    @Immutable
    data class State(
        val animes: List<Anime> = emptyList(),
        val folders: List<AnimeFolder> = emptyList(),
        val tabModes: Map<AnimeStatus, AnimeTabMode> = emptyMap(),
        val status: AnimeStatus = AnimeStatus.PLAN_TO_WATCH,
        val connection: MalAnimeSync.Connection = MalAnimeSync.Connection.UNKNOWN,
        val malUsername: String = "",
        val isSyncing: Boolean = false,
        val isLoading: Boolean = true,
    ) {
        private val itemsByTab: Map<AnimeStatus, List<AnimeShelf.Item>> = AnimeStatus.entries.associateWith { tab ->
            AnimeShelf.itemsInTab(animes, folders, tab, modeFor(tab))
        }

        private val allFolders: List<AnimeShelf.Folder> = AnimeShelf.buildFolders(animes, folders)

        fun modeFor(tab: AnimeStatus): AnimeTabMode = tabModes[tab] ?: AnimeTabMode.RECENT

        /** Entradas da aba [tab] (animes soltos e pastas), já na ordem da aba. */
        fun itemsFor(tab: AnimeStatus): List<AnimeShelf.Item> = itemsByTab[tab].orEmpty()

        /** Quantas entradas tem a aba (uma pasta conta como uma). */
        fun countFor(tab: AnimeStatus): Int = itemsFor(tab).size

        fun folderById(folderId: String): AnimeShelf.Folder? = allFolders.find { it.folder.id == folderId }

        /** Todas as pastas (para "Move to folder"), por nome. */
        val folderChoices: List<AnimeShelf.Folder> get() = allFolders
    }

    private companion object {
        const val FIRST_FOLDER_POSITION = 0.0
    }
}
