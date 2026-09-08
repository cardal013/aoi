package eu.kanade.tachiyomi.ui.more.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.account.UserLibraryItem
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class CloudLibraryScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<CloudLibraryViewModel>()
        val state by viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        val tabs = listOf(
            StatusTab("reading", stringResource(MR.strings.reading_status_reading)),
            StatusTab("completed", stringResource(MR.strings.reading_status_completed)),
            StatusTab("dropped", stringResource(MR.strings.reading_status_dropped)),
            StatusTab("plan_to_read", stringResource(MR.strings.reading_status_plan_to_read)),
        )

        val pagerState = rememberPagerState { tabs.size }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Cloud Library",
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch {
                                    pagerState.scrollToPage(index)
                                }
                            },
                            text = { Text(tab.title) }
                        )
                    }
                }

                if (state.isLoading) {
                    LoadingScreen(Modifier.fillMaxSize())
                    return@Column
                }

                if (state.error != null) {
                    EmptyScreen(
                        message = state.error!!,
                        modifier = Modifier.fillMaxSize()
                    )
                    return@Column
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { page ->
                    val statusKey = tabs[page].key
                    val items = state.itemsByStatus[statusKey].orEmpty()

                    if (items.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Nenhum manga em ${tabs[page].title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        CloudLibraryGrid(items = items)
                    }
                }
            }
        }
    }

    @Composable
    private fun CloudLibraryGrid(items: List<UserLibraryItem>) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.mangaId }) { item ->
                MangaCompactGridItem(
                    coverData = MangaCover(
                        mangaId = -1, // Not used for remote
                        sourceId = -1, // Not used for remote
                        isMangaFavorite = item.isFavorite,
                        url = item.thumbnailUrl,
                        lastModified = 0L
                    ),
                    title = item.title,
                    onClick = { /* TODO: Open remote manga details if implemented */ },
                    onLongClick = { /* No-op */ }
                )
            }
        }
    }

    private data class StatusTab(val key: String, val title: String)
}
