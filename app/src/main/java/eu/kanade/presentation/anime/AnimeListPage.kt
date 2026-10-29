package eu.kanade.presentation.anime

import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.ui.anime.watch.AnimeWatchScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.data.anime.MalAnimeSync
import eu.kanade.tachiyomi.ui.anime.AnimeListViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.anime.AnimeShelf
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.model.AnimeTabMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.util.plus

/**
 * Conteúdo do separador Watch. Sem MAL ligado só mostra "Connect MyAnimeList".
 * Com o MAL ligado (opção D escolhida pelo Afonso): pílula com os estados e a contagem (deslizar a grelha
 * também muda de estado), com o menu para desligar o MAL ao lado, e capas compactas com o título por cima (o botão Recentes/Manual está na barra de cima, à esquerda da lupa:
 * [AnimeTabModeButton]); grelha com animes soltos e pastas, puxar para baixo para ir buscar a lista ao MAL
 * e botão para acrescentar. Carregar sem largar: largar sem mexer abre o menu; mexer arrasta (passa a Manual).
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
    val navigator = LocalNavigator.currentOrThrow
    var openedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<ShelfDialog?>(null) }

    val query = searchQuery?.trim().orEmpty()
    val statuses = AnimeStatus.entries
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = statuses.indexOf(state.status)) { statuses.size }
    // O separador mostrado é o estado escolhido (o botão Recentes/Manual e o + seguem-no)
    LaunchedEffect(pagerState.settledPage) { onSelectStatus(statuses[pagerState.settledPage]) }
    val gridPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = contentPadding.calculateBottomPadding() + 88.dp)

    Box(modifier = Modifier.fillMaxSize()) {
        // A barra de cima é do ecrã (WatchTab): a pílula dos estados fica logo por baixo dela (opção D)
        Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    statuses.forEachIndexed { index, status ->
                        SegmentedButton(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            shape = SegmentedButtonDefaults.itemShape(index, statuses.size),
                            icon = {},
                        ) {
                            Text("${status.label()} · ${state.countFor(status)}", maxLines = 1)
                        }
                    }
                }
                MalMenu(malUsername = state.malUsername, onDisconnect = onDisconnect)
            }

            PullRefresh(
                refreshing = state.isSyncing,
                enabled = true,
                onRefresh = onRefresh,
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.swipeBetweenPages(pagerState, scope),
                    key = { statuses[it] },
                    // Um deslizar curto e lento também muda de separador (com 0.5 voltava para trás)
                    flingBehavior = PagerDefaults.flingBehavior(pagerState, snapPositionalThreshold = 0.2f),
                ) { page ->
                    val tab = statuses[page]
                    val tabItems = state.itemsFor(tab)
                    val visibleItems = remember(tabItems, query) { tabItems.filter { itemMatchesQuery(it, query) } }
                    StatusGrid(
                        tab = tab,
                        visibleItems = visibleItems,
                        query = query,
                        gridPadding = gridPadding,
                        navigator = navigator,
                        onClickAnime = { openedId = it },
                        onOpenFolder = onOpenFolder,
                        onMenu = { dialog = it },
                        onTabReordered = onTabReordered,
                    )
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
            onWatch = {
                openedId = null
                navigator.push(AnimeWatchScreen(opened.malId))
            },
        )
    }
}

/**
 * Deslizar na horizontal muda de separador mesmo quando o pager não fica com o gesto: com a grelha ainda
 * a deslizar (ou a esticar no fim da lista), o toque seguinte é logo dela e o pager não mexe.
 * Só vê os toques (não os consome) e ignora o arrastar das capas, que começa depois de carregar sem largar.
 */
private fun Modifier.swipeBetweenPages(pagerState: PagerState, scope: CoroutineScope): Modifier =
    pointerInput(pagerState) {
        val minDistance = 48.dp.toPx()
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startPage = pagerState.currentPage
            var pagerMoved = pagerState.isScrollInProgress
            var afterLongPress = false
            var movedPastSlop = false
            var position = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                position = change.position
                pagerMoved = pagerMoved || pagerState.isScrollInProgress
                if (!movedPastSlop && (position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    movedPastSlop = true
                    afterLongPress = change.uptimeMillis - down.uptimeMillis >= longPressTimeout
                }
                if (!change.pressed) break
            }
            val dx = position.x - down.position.x
            val dy = position.y - down.position.y
            val horizontalSwipe = abs(dx) > minDistance && abs(dx) > 2 * abs(dy)
            if (pagerMoved || afterLongPress || !horizontalSwipe) return@awaitEachGesture
            val target = (startPage + if (dx > 0) -1 else 1).coerceIn(0, pagerState.pageCount - 1)
            if (target != startPage) {
                scope.launch { pagerState.animateScrollToPage(target) }
            }
        }
    }

/** A grelha de um separador (Plan to Watch ou Completed), com os animes soltos e as pastas. */
@Composable
private fun StatusGrid(
    tab: AnimeStatus,
    visibleItems: List<AnimeShelf.Item>,
    query: String,
    gridPadding: PaddingValues,
    navigator: Navigator,
    onClickAnime: (Long) -> Unit,
    onOpenFolder: (folderId: String) -> Unit,
    onMenu: (ShelfDialog) -> Unit,
    onTabReordered: (tab: AnimeStatus, orderedKeys: List<String>, movedKey: String) -> Unit,
) {
    if (visibleItems.isEmpty()) {
        EmptyScreen(stringRes = if (query.isEmpty()) MR.strings.anime_empty else MR.strings.no_results_found)
        return
    }
    ReorderableCoverGrid(
        items = visibleItems,
        key = { it.key },
        // Com uma pesquisa a lista está filtrada: arrastar baralhava a ordem real
        dragEnabled = query.isEmpty(),
        contentPadding = gridPadding,
        onClick = { item ->
            when (item) {
                is AnimeShelf.LooseAnime -> onClickAnime(item.anime.malId)
                is AnimeShelf.Folder -> onOpenFolder(item.folder.id)
            }
        },
        onMenu = { item ->
            onMenu(
                when (item) {
                    is AnimeShelf.LooseAnime -> ShelfDialog.AnimeMenu(item.anime)
                    is AnimeShelf.Folder -> ShelfDialog.FolderMenu(item.folder.id)
                },
            )
        },
        onReordered = { orderedKeys, movedKey -> onTabReordered(tab, orderedKeys, movedKey) },
    ) { item ->
        val onContinue = AnimeShelf.animeToContinue(item)?.let { anime ->
            { navigator.push(AnimeWatchScreen(anime.malId, autoPlay = true)) }
        }
        when (item) {
            is AnimeShelf.LooseAnime -> AnimeGridItem(
                anime = item.anime,
                showStatus = false,
                onContinue = onContinue,
            )
            is AnimeShelf.Folder -> FolderGridItem(folder = item, onContinue = onContinue)
        }
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
 * Um anime na grelha (opção D): capa com o título por cima, a barra dos episódios vistos no fundo (Plan to Watch
 * já começado; sem total conhecido fica o "3/?" em cima) e, com [onContinue], o botão ▶ (episódio seguinte).
 * Com [showStatus] (dentro de uma pasta, onde cada temporada tem o seu estado) mostra também o estado.
 */
@Composable
fun AnimeGridItem(
    anime: Anime,
    showStatus: Boolean,
    onContinue: (() -> Unit)? = null,
) {
    val started = anime.status == AnimeStatus.PLAN_TO_WATCH && anime.episodesWatched > 0
    val total = anime.numEpisodes?.takeIf { it > 0 }
    CompactCover(
        coverUrl = anime.coverUrl,
        title = anime.title,
        progress = if (started && total != null) (anime.episodesWatched.toFloat() / total).coerceIn(0f, 1f) else null,
        onContinue = onContinue,
    ) {
        if (showStatus) Badge(text = anime.status.label())
        if (started && total == null) Badge(text = episodesShort(anime))
    }
}

/** Uma pasta na grelha: aparece como um anime (com a capa escolhida), com o ícone e o número de animes. */
@Composable
private fun FolderGridItem(folder: AnimeShelf.Folder, onContinue: (() -> Unit)?) {
    CompactCover(
        coverUrl = folder.cover?.coverUrl,
        title = folder.folder.name,
        progress = null,
        onContinue = onContinue,
    ) {
        Badge(imageVector = MaterialSymbols.Rounded.Folder)
        Badge(text = folder.members.size.toString())
    }
}

/** Capa com o título em baixo sobre um escurecido, emblemas em cima, ▶ ao lado do título e barra de progresso. */
@Composable
private fun CompactCover(
    coverUrl: String?,
    title: String,
    progress: Float?,
    onContinue: (() -> Unit)?,
    badges: @Composable RowScope.() -> Unit,
) {
    Box(modifier = Modifier.clip(MaterialTheme.shapes.extraSmall)) {
        MangaCover.Book(data = coverUrl, modifier = Modifier.fillMaxWidth(), contentDescription = title)
        BadgeGroup(modifier = Modifier.padding(4.dp), content = badges)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xCC000000))),
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, top = 20.dp, end = 4.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(shadow = Shadow(blurRadius = 4f)),
                    modifier = Modifier.weight(1f),
                )
                onContinue?.let { ContinueWatchingButton(onClick = it, modifier = Modifier.padding(start = 4.dp)) }
            }
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

/** O mesmo botão ▶ do "continuar a ler" dos mangas (`CommonMangaItem`), ao lado do título na capa. */
@Composable
private fun ContinueWatchingButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
        ),
        modifier = modifier.size(28.dp),
    ) {
        Icon(
            imageVector = MaterialSymbols.RoundedFilled.PlayArrow,
            contentDescription = stringResource(MR.strings.anime_continue),
            modifier = Modifier.size(18.dp),
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
