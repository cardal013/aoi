package eu.kanade.tachiyomi.ui.anime

import eu.kanade.tachiyomi.ui.anime.watch.AnimeWatchScreen
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.anime.AnimeDetailsSheet
import eu.kanade.presentation.anime.AnimeGridItem
import eu.kanade.presentation.anime.AnimeShelfDialogs
import eu.kanade.presentation.anime.ReorderableCoverGrid
import eu.kanade.presentation.anime.ShelfDialog
import eu.kanade.presentation.anime.label
import eu.kanade.presentation.anime.myAnimeListUrl
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Add
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

/**
 * Ecrã de uma pasta de animes: todas as temporadas lá dentro, cada uma com o seu estado.
 * Botão de ordem (data crescente <-> decrescente; à mão volta à data), arrastar passa a ordem à mão,
 * menu com capa, nome e apagar. Carregar sem largar num anime e largar sem mexer abre o menu do anime.
 * O botão + abre a pesquisa e o que se acrescenta lá fica nesta pasta.
 * Se a pasta deixar de existir (apagada aqui ou noutro aparelho), o ecrã fecha.
 */
class AnimeFolderScreen(private val folderId: String) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        val viewModel = metroViewModel<AnimeListViewModel>()
        val state by viewModel.state.collectAsState()
        val shelfActions = remember(viewModel) { viewModel.shelfActions() }
        var dialog by remember { mutableStateOf<ShelfDialog?>(null) }
        var openedId by rememberSaveable { mutableStateOf<Long?>(null) }

        val folder = state.folderById(folderId)
        if (folder == null) {
            // Ainda a carregar, ou a pasta já não existe
            if (!state.isLoading) {
                LaunchedEffect(Unit) { navigator.pop() }
            }
            return
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = folder.folder.name,
                    subtitle = folder.folder.sortOrder.label(),
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(
                                        MR.strings.anime_folder_sort,
                                        folder.folder.sortOrder.label(),
                                    ),
                                    icon = MaterialSymbols.AutoMirroredRounded.Sort,
                                    onClick = { viewModel.toggleFolderSortOrder(folder.folder) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.anime_folder_cover),
                                    onClick = { dialog = ShelfDialog.ChooseCover(folderId) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.anime_folder_rename),
                                    onClick = { dialog = ShelfDialog.RenameFolder(folderId) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.anime_folder_delete),
                                    onClick = { dialog = ShelfDialog.DeleteFolder(folderId) },
                                ),
                            ),
                        )
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { navigator.push(AnimeSearchScreen(AnimeStatus.PLAN_TO_WATCH, folderId)) },
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Add,
                        contentDescription = stringResource(MR.strings.anime_add),
                    )
                }
            },
        ) { contentPadding ->
            if (folder.members.isEmpty()) {
                EmptyScreen(stringRes = MR.strings.anime_folder_empty, modifier = Modifier.padding(contentPadding))
            } else {
                ReorderableCoverGrid(
                    items = folder.members,
                    key = { it.malId.toString() },
                    dragEnabled = true,
                    contentPadding = contentPadding +
                        PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 88.dp),
                    onClick = { anime -> openedId = anime.malId },
                    onMenu = { anime -> dialog = ShelfDialog.AnimeMenu(anime) },
                    onReordered = { orderedKeys, _ ->
                        viewModel.onFolderReordered(folderId, orderedKeys.map { it.toLong() })
                    },
                ) { anime ->
                    AnimeGridItem(
                        anime = anime,
                        showStatus = true,
                        onContinue = { navigator.push(AnimeWatchScreen(anime.malId)) }
                            .takeIf { anime.status != AnimeStatus.COMPLETED },
                    )
                }
            }
        }

        AnimeShelfDialogs(
            dialog = dialog,
            folders = state.folderChoices,
            actions = shelfActions,
            onChangeDialog = { dialog = it },
        )

        val opened = folder.members.find { it.malId == openedId }
        if (opened != null) {
            AnimeDetailsSheet(
                anime = opened,
                onDismissRequest = { openedId = null },
                onChangeStatus = { viewModel.setStatus(opened, it) },
                onChangeEpisodes = { viewModel.setEpisodesWatched(opened, it) },
                onRemove = {
                    openedId = null
                    viewModel.remove(opened)
                },
                onOpenMyAnimeList = { uriHandler.openUri(myAnimeListUrl(opened.malId)) },
                onWatch = {
                    openedId = null
                    navigator.push(AnimeWatchScreen(opened.malId))
                },
            )
        }
    }
}
