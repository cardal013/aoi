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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
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
 * Com o MAL ligado: filtro por estado em cima (com o menu para desligar o MAL), grelha, puxar para baixo
 * para ir buscar a lista ao MAL e botão para acrescentar.
 */
@Composable
fun AnimeListPage(
    state: AnimeListViewModel.State,
    searchQuery: String?,
    contentPadding: PaddingValues,
    onSelectStatus: (AnimeStatus) -> Unit,
    onClickAdd: () -> Unit,
    onChangeStatus: (Anime, AnimeStatus) -> Unit,
    onChangeEpisodes: (Anime, Long) -> Unit,
    onRemove: (Anime) -> Unit,
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

    val query = searchQuery?.trim().orEmpty()
    val visible = remember(state.animes, state.status, query) {
        state.animes.filter { anime -> anime.status == state.status && matchesQuery(anime, query) }
    }

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
                if (visible.isEmpty()) {
                    EmptyScreen(
                        stringRes = if (query.isEmpty()) MR.strings.anime_empty else MR.strings.no_results_found,
                        modifier = Modifier.padding(contentPadding),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        contentPadding = contentPadding + PaddingValues(start = 8.dp, end = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.malId }) { anime ->
                            AnimeGridItem(anime = anime, onClick = { openedId = anime.malId })
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

/** Se o anime aparece na pesquisa da biblioteca (título original ou inglês). Pesquisa vazia = todos. */
private fun matchesQuery(anime: Anime, query: String): Boolean {
    if (query.isEmpty()) {
        return true
    }
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

@Composable
private fun AnimeGridItem(
    anime: Anime,
    onClick: () -> Unit,
) {
    Column {
        Box {
            MangaCover.Book(
                data = anime.coverUrl,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = anime.title,
                onClick = onClick,
            )
            if (anime.status == AnimeStatus.WATCHING) {
                BadgeGroup(modifier = Modifier.padding(4.dp)) {
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

@Composable
fun AnimeStatus.label(): String = stringResource(
    when (this) {
        AnimeStatus.WATCHING -> MR.strings.anime_status_watching
        AnimeStatus.PLAN_TO_WATCH -> MR.strings.anime_status_plan_to_watch
        AnimeStatus.COMPLETED -> MR.strings.anime_status_completed
    },
)

internal fun episodesShort(anime: Anime): String = "${anime.episodesWatched}/${anime.numEpisodes ?: "?"}"

internal fun myAnimeListUrl(malId: Long): String = "https://myanimelist.net/anime/$malId"
