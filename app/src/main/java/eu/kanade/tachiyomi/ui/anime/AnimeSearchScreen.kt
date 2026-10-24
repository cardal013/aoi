package eu.kanade.tachiyomi.ui.anime

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.anime.animeInfoLine
import eu.kanade.presentation.anime.label
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.util.plus

/**
 * Pesquisa no MyAnimeList para acrescentar animes. Os novos entram no estado [defaultStatus], que se pode mudar.
 * Tocar acrescenta um; carregar sem largar começa a selecionar vários (a seleção fica ao mudar a pesquisa)
 * e o botão em baixo acrescenta-os todos com um só diálogo. Com [folderId] (aberta dentro de uma pasta)
 * os animes ficam nessa pasta.
 */
class AnimeSearchScreen(
    private val defaultStatus: AnimeStatus,
    private val folderId: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<AnimeSearchViewModel>()
        val state by viewModel.state.collectAsState()
        val inList by viewModel.inList.collectAsState()
        val selected = remember { mutableStateMapOf<Long, Anime>() }
        var picked by remember { mutableStateOf<List<Anime>>(emptyList()) }
        val isSelecting = selected.isNotEmpty()

        BackHandler(enabled = isSelecting) { selected.clear() }

        Scaffold(
            topBar = {
                SearchToolbar(
                    searchQuery = state.query,
                    onChangeSearchQuery = { viewModel.onQueryChange(it.orEmpty()) },
                    placeholderText = stringResource(MR.strings.anime_search_hint),
                    navigateUp = navigator::pop,
                    onClickCloseSearch = navigator::pop,
                )
            },
            floatingActionButton = {
                if (isSelecting) {
                    ExtendedFloatingActionButton(
                        text = { Text(stringResource(MR.strings.anime_add_selected, selected.size)) },
                        icon = { Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null) },
                        onClick = { picked = selected.values.toList() },
                    )
                }
            },
        ) { contentPadding ->
            when {
                state.isLoading && state.results.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.hasError -> EmptyScreen(
                    stringRes = MR.strings.anime_search_error,
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_retry,
                            icon = MaterialSymbols.Rounded.Refresh,
                            onClick = viewModel::retry,
                        ),
                    ),
                )
                state.results.isEmpty() -> EmptyScreen(
                    stringRes = if (state.query.trim().length < AnimeSearchViewModel.MIN_QUERY_LENGTH) {
                        MR.strings.anime_search_prompt
                    } else {
                        MR.strings.no_results_found
                    },
                    modifier = Modifier.padding(contentPadding),
                )
                else -> LazyColumn(contentPadding = contentPadding + PaddingValues(bottom = 88.dp)) {
                    items(state.results, key = { it.malId }) { anime ->
                        val toggleSelection = {
                            if (selected.remove(anime.malId) == null) {
                                selected[anime.malId] = anime
                            }
                        }
                        SearchResultRow(
                            anime = anime,
                            status = inList[anime.malId],
                            isSelected = anime.malId in selected,
                            onClick = {
                                if (isSelecting) toggleSelection() else picked = listOf(anime)
                            },
                            onLongClick = toggleSelection,
                        )
                    }
                }
            }
        }

        if (picked.isNotEmpty()) {
            val onlyOne = picked.singleOrNull()
            AddAnimeDialog(
                initialStatus = onlyOne?.let { inList[it.malId] } ?: defaultStatus,
                onDismissRequest = { picked = emptyList() },
                onConfirm = { status ->
                    viewModel.add(picked, status, folderId)
                    picked = emptyList()
                    selected.clear()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    anime: Anime,
    status: AnimeStatus?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val background = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(data = anime.coverUrl, modifier = Modifier.width(56.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = anime.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = animeInfoLine(anime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status != null) {
                Text(
                    text = stringResource(MR.strings.anime_in_list, status.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun AddAnimeDialog(
    initialStatus: AnimeStatus,
    onDismissRequest: () -> Unit,
    onConfirm: (AnimeStatus) -> Unit,
) {
    var selected by remember { mutableStateOf(initialStatus) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.anime_add_to)) },
        text = {
            Column {
                AnimeStatus.entries.forEach { status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == status,
                                onClick = { selected = status },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == status, onClick = null)
                        Text(text = status.label(), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
