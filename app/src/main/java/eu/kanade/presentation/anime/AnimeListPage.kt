package eu.kanade.presentation.anime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.data.anime.MalAnimeSync
import eu.kanade.tachiyomi.ui.anime.AnimeListViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.domain.anime.AnimeShelf
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.model.AnimeTabMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.util.plus

/**
 * Página Anime da biblioteca. Sem MAL ligado só mostra "Connect MyAnimeList".
 * Com o MAL ligado: estados em cima (com o menu para desligar o MAL; o botão Recentes/Manual está na barra
 * de cima, à esquerda da lupa: [AnimeTabModeButton]), grelha
 * com animes soltos e pastas, puxar para baixo para ir buscar a lista ao MAL e botão para acrescentar.
 * Carregar sem largar: largar sem mexer abre o menu; mexer arrasta (a aba passa a Manual).
 */
@Composable
fun AnimeListPage(
    state: AnimeListViewModel.State,
    searchQuery: String?,
    contentPadding: PaddingValues,
    onSelectStatus: (AnimeStatus) -> Unit,
    onClickAdd: () -> Unit,
    onOpenFolder: (folderId: String) -> Unit,
    onChangeStatus: (Anime, AnimeStatus) -> Unit,
    onChangeEpisodes: (Anime, Long) -> Unit,
    onRemove: (Anime) -> Unit,
    onTabReordered: (tab: AnimeStatus, orderedKeys: List<String>, movedKey: String) -> Unit,
    shelfActions: AnimeShelfActions,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
) {
    val isConnected = state.connection == MalAnimeSync.Connection.CONNECTED
    if (!isConnected) {
        NotConnectedPage(state = state, contentPadding = contentPadding, onConnect = onConnect)
        return
    }

    val uriHandler = LocalUriHandler.current
    var openedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<ShelfDialog?>(null) }

    val query = searchQuery?.trim().orEmpty()
    val tab = state.status
    val tabItems = state.itemsFor(tab)
    val visibleItems = remember(tabItems, query) { tabItems.filter { itemMatchesQuery(it, query) } }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChips(state = state, onSelectStatus = onSelectStatus, modifier = Modifier.weight(1f))
                MalMenu(malUsername = state.malUsername, onDisconnect = onDisconnect)
            }

            PullRefresh(
                refreshing = state.isSyncing,
                enabled = true,
                onRefresh = onRefresh,
            ) {
                if (visibleItems.isEmpty()) {
                    EmptyScreen(
                        stringRes = if (query.isEmpty()) MR.strings.anime_empty else MR.strings.no_results_found,
                        modifier = Modifier.padding(contentPadding),
                    )
                } else {
                    ReorderableCoverGrid(
                        items = visibleItems,
                        key = { it.key },
                        // Com uma pesquisa a lista está filtrada: arrastar baralhava a ordem real
                        dragEnabled = query.isEmpty(),
                        contentPadding = contentPadding + PaddingValues(start = 8.dp, end = 8.dp, bottom = 88.dp),
                        onClick = { item ->
                            when (item) {
                                is AnimeShelf.LooseAnime -> openedId = item.anime.malId
                                is AnimeShelf.Folder -> onOpenFolder(item.folder.id)
                            }
                        },
                        onMenu = { item ->
                            dialog = when (item) {
                                is AnimeShelf.LooseAnime -> ShelfDialog.AnimeMenu(item.anime)
                                is AnimeShelf.Folder -> ShelfDialog.FolderMenu(item.folder.id)
                            }
                        },
                        onReordered = { orderedKeys, movedKey -> onTabReordered(tab, orderedKeys, movedKey) },
                    ) { item ->
                        when (item) {
                            is AnimeShelf.LooseAnime -> AnimeGridItem(anime = item.anime, showStatus = false)
                            is AnimeShelf.Folder -> FolderGridItem(folder = item)
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onClickAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = stringResource(MR.strings.anime_add))
        }
    }

    AnimeShelfDialogs(
        dialog = dialog,
        folders = state.folderChoices,
        actions = shelfActions,
        onChangeDialog = { dialog = it },
    )

    val opened = state.animes.find { it.malId == openedId }
    if (opened != null) {
        AnimeDetailsSheet(
            anime = opened,
            onDismissRequest = { openedId = null },
            onChangeStatus = { onChangeStatus(opened, it) },
            onChangeEpisodes = { onChangeEpisodes(opened, it) },
            onRemove = {
                openedId = null
                onRemove(opened)
            },
            onOpenMyAnimeList = { uriHandler.openUri(myAnimeListUrl(opened.malId)) },
        )
    }
}

/**
 * Se a entrada aparece na pesquisa da biblioteca. Um anime pelo título (original ou inglês);
 * uma pasta pelo nome ou por algum anime lá dentro. Pesquisa vazia = todas.
 */
private fun itemMatchesQuery(item: AnimeShelf.Item, query: String): Boolean {
    if (query.isEmpty()) {
        return true
    }
    return when (item) {
        is AnimeShelf.LooseAnime -> animeMatchesQuery(item.anime, query)
        is AnimeShelf.Folder -> {
            val nameMatches = item.folder.name.contains(query, ignoreCase = true)
            nameMatches || item.members.any { animeMatchesQuery(it, query) }
        }
    }
}

private fun animeMatchesQuery(anime: Anime, query: String): Boolean {
    val titleMatches = anime.title.contains(query, ignoreCase = true)
    val englishTitleMatches = anime.titleEnglish?.contains(query, ignoreCase = true) == true
    return titleMatches || englishTitleMatches
}

/** Sem MAL ligado: a aba só mostra o botão "Connect MyAnimeList" (ou o aviso de entrar na conta do Aoi). */
@Composable
private fun NotConnectedPage(
    state: AnimeListViewModel.State,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
) {
    val checkingConnection = state.connection == MalAnimeSync.Connection.UNKNOWN && state.isSyncing
    if (state.isLoading || checkingConnection) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.connection == MalAnimeSync.Connection.NO_ACCOUNT) {
        EmptyScreen(stringRes = MR.strings.anime_mal_needs_account, modifier = Modifier.padding(contentPadding))
        return
    }

    EmptyScreen(
        stringRes = MR.strings.anime_mal_connect_summary,
        modifier = Modifier.padding(contentPadding),
        actions = listOf(
            EmptyScreenAction(
                stringRes = MR.strings.anime_mal_connect,
                icon = MaterialSymbols.AutoMirroredRounded.OpenInNew,
                onClick = onConnect,
            ),
        ),
    )
}

@Composable
private fun StatusChips(
    state: AnimeListViewModel.State,
    onSelectStatus: (AnimeStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimeStatus.entries.forEach { status ->
            FilterChip(
                selected = status == state.status,
                onClick = { onSelectStatus(status) },
                label = { Text("${status.label()} · ${state.countFor(status)}") },
            )
        }
    }
}

/** Botão do modo da aba (na barra de cima da biblioteca): mostra o modo atual e tocar troca entre Recentes e Manual. */
@Composable
fun AnimeTabModeButton(
    mode: AnimeTabMode,
    onChangeMode: (AnimeTabMode) -> Unit,
) {
    val nextMode = if (mode == AnimeTabMode.RECENT) AnimeTabMode.MANUAL else AnimeTabMode.RECENT
    val label = if (mode == AnimeTabMode.RECENT) MR.strings.anime_mode_recent else MR.strings.anime_mode_manual
    TextButton(onClick = { onChangeMode(nextMode) }) {
        Text(stringResource(label))
    }
}

/** Menu ao lado dos estados: "Disconnect MyAnimeList", com confirmação. */
@Composable
private fun MalMenu(
    malUsername: String,
    onDisconnect: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDisconnect by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(imageVector = MaterialSymbols.Rounded.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.anime_mal_disconnect)) },
                onClick = {
                    menuOpen = false
                    confirmingDisconnect = true
                },
            )
        }
    }

    if (confirmingDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmingDisconnect = false },
            title = { Text(stringResource(MR.strings.anime_mal_disconnect)) },
            text = { Text(stringResource(MR.strings.anime_mal_disconnect_confirm, malUsername)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDisconnect = false
                        onDisconnect()
                    },
                ) {
                    Text(stringResource(MR.strings.anime_mal_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDisconnect = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

/**
 * Capa e título de um anime na grelha. Em Plan to Watch já começado mostra os episódios vistos.
 * Com [showStatus] (dentro de uma pasta, onde cada temporada tem o seu estado) mostra também o estado.
 */
@Composable
fun AnimeGridItem(
    anime: Anime,
    showStatus: Boolean,
) {
    Column {
        Box {
            MangaCover.Book(
                data = anime.coverUrl,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = anime.title,
            )
            BadgeGroup(modifier = Modifier.padding(4.dp)) {
                if (showStatus) {
                    Badge(text = anime.status.label())
                }
                if (anime.status == AnimeStatus.PLAN_TO_WATCH && anime.episodesWatched > 0) {
                    Badge(text = episodesShort(anime))
                }
            }
        }
        Text(
            text = anime.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** Uma pasta na grelha: aparece como um anime (com a capa escolhida), com o ícone e o número de animes. */
@Composable
private fun FolderGridItem(folder: AnimeShelf.Folder) {
    Column {
        Box {
            MangaCover.Book(
                data = folder.cover?.coverUrl,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = folder.folder.name,
            )
            BadgeGroup(modifier = Modifier.padding(4.dp)) {
                Badge(imageVector = MaterialSymbols.Rounded.Folder)
                Badge(text = folder.members.size.toString())
            }
        }
        Text(
            text = folder.folder.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
fun AnimeStatus.label(): String = stringResource(
    when (this) {
        AnimeStatus.PLAN_TO_WATCH -> MR.strings.anime_status_plan_to_watch
        AnimeStatus.COMPLETED -> MR.strings.anime_status_completed
    },
)

internal fun episodesShort(anime: Anime): String = "${anime.episodesWatched}/${anime.numEpisodes ?: "?"}"

internal fun myAnimeListUrl(malId: Long): String = "https://myanimelist.net/anime/$malId"
