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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.anime.AnimeListViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

/** Página Anime da biblioteca: filtro por estado em cima, grelha e botão para acrescentar. */
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
) {
    val uriHandler = LocalUriHandler.current
    var openedId by rememberSaveable { mutableStateOf<Long?>(null) }

    val query = searchQuery?.trim().orEmpty()
    val visible = remember(state.animes, state.status, query) {
        state.animes.filter { anime ->
            anime.status == state.status &&
                (
                    query.isEmpty() ||
                        anime.title.contains(query, ignoreCase = true) ||
                        anime.titleEnglish?.contains(query, ignoreCase = true) == true
                    )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    items(visible, key = { it.anilistId }) { anime ->
                        AnimeGridItem(anime = anime, onClick = { openedId = anime.anilistId })
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

    val opened = state.animes.find { it.anilistId == openedId }
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
            onOpenAniList = { uriHandler.openUri(anilistUrl(opened.anilistId)) },
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

internal fun episodesShort(anime: Anime): String = "${anime.episodesWatched}/${anime.episodes ?: "?"}"

internal fun anilistUrl(anilistId: Long): String = "https://anilist.co/anime/$anilistId"
