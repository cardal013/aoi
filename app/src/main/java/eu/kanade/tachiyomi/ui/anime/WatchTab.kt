package eu.kanade.tachiyomi.ui.anime

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.anime.AnimeListPage
import eu.kanade.presentation.anime.AnimeTabModeButton
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.anime.MalAnimeSync
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Separador Watch (no lugar do History): a lista de animes do MAL com as pastas, que antes era a última
 * página da biblioteca. O Read ficou só com os mangas.
 */
data object WatchTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 2u,
            title = stringResource(MR.strings.label_watch),
            icon = painterResource(R.drawable.ic_watch_24dp),
        )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<AnimeListViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val shelfActions = remember(viewModel) { viewModel.shelfActions() }
        val showCount by remember { context.appGraph.libraryPreferences.categoryNumberOfItems }.collectAsState()
        var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) { viewModel.onPageOpened() }

        Scaffold(
            topBar = {
                LibraryToolbar(
                    hasActiveFilters = false,
                    selectedCount = 0,
                    title = LibraryToolbarTitle(
                        stringResource(MR.strings.label_watch),
                        state.animes.size.takeIf { showCount },
                    ),
                    onClickUnselectAll = {},
                    onClickSelectAll = {},
                    onClickInvertSelection = {},
                    onClickFilter = {},
                    onClickRefresh = {},
                    onClickGlobalUpdate = {},
                    onClickOpenRandomManga = {},
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    scrollBehavior = null,
                    showMangaActions = false,
                    animeActions = {
                        if (state.connection == MalAnimeSync.Connection.CONNECTED) {
                            val tab = state.status
                            AnimeTabModeButton(
                                mode = state.modeFor(tab),
                                onChangeMode = { viewModel.setTabMode(tab, it) },
                            )
                        }
                    },
                )
            },
        ) { contentPadding ->
            AnimeListPage(
                state = state,
                searchQuery = searchQuery,
                contentPadding = contentPadding,
                onSelectStatus = viewModel::selectStatus,
                onClickAdd = { navigator.push(AnimeSearchScreen(state.status)) },
                onOpenFolder = { folderId -> navigator.push(AnimeFolderScreen(folderId)) },
                onChangeStatus = viewModel::setStatus,
                onChangeEpisodes = viewModel::setEpisodesWatched,
                onRemove = viewModel::remove,
                onTabReordered = viewModel::onTabReordered,
                shelfActions = shelfActions,
                onConnect = {
                    viewModel.connect(
                        openUrl = { url -> context.openInBrowser(url) },
                        onError = { context.toast(MR.strings.anime_mal_connect_failed) },
                    )
                },
                onDisconnect = {
                    viewModel.disconnect(
                        onError = { context.toast(MR.strings.anime_mal_disconnect_failed) },
                    )
                },
                onRefresh = viewModel::refresh,
            )
        }

        BackHandler(enabled = searchQuery != null) { searchQuery = null }
    }
}
