package eu.kanade.tachiyomi.ui.anime.watch

import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.data.anime.AnimeDownloads
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction

/** Ver e transferir os episódios de um anime da lista do MAL ([malId]). */
@OptIn(UnstableApi::class)
class AnimeWatchScreen(private val malId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<AnimeWatchViewModel>()
        LaunchedEffect(malId) { viewModel.init(malId) }
        val state by viewModel.state.collectAsState()
        val extensions by viewModel.extensionsState.collectAsState()
        val downloads by viewModel.downloads.items.collectAsState()
        val sources = extensions.installed.flatMap { it.sources }
        val failedText = stringResource(MR.strings.anime_download_failed)

        // O Media3 só avisa quando o estado muda: a percentagem lê-se de segundo a segundo
        LaunchedEffect(Unit) {
            while (true) {
                viewModel.downloads.refresh()
                delay(1_000)
            }
        }

        LaunchedEffect(state.message) {
            if (state.message != null) {
                Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
                viewModel.messageShown()
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = state.anime?.title ?: stringResource(MR.strings.anime_watch),
                    subtitle = if (state.choosing) {
                        stringResource(MR.strings.anime_watch_choose_source)
                    } else {
                        viewModel.linkedSource()?.name ?: state.link?.title
                    },
                    navigateUp = {
                        if (state.choosing && state.link != null) viewModel.stopChoosing() else navigator.pop()
                    },
                    actions = {
                        if (!state.choosing && state.link != null) {
                            IconButton(onClick = viewModel::startChoosing) {
                                Icon(MaterialSymbols.Rounded.Sync, stringResource(MR.strings.anime_watch_change_source))
                            }
                        }
                        IconButton(onClick = { navigator.push(AnimeExtensionsScreen()) }) {
                            Icon(MaterialSymbols.Rounded.Explore, stringResource(MR.strings.anime_extensions))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    !state.ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.choosing && sources.isEmpty() -> EmptyScreen(
                        stringRes = MR.strings.anime_watch_no_extensions,
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.anime_extensions,
                                icon = MaterialSymbols.Rounded.Explore,
                                onClick = { navigator.push(AnimeExtensionsScreen()) },
                            ),
                        ),
                    )
                    state.choosing -> ChooseSource(viewModel, state, sources)
                    else -> Episodes(
                        state = state,
                        downloads = downloads,
                        onPlay = { episode ->
                            val sourceId = viewModel.linkedSource()?.id ?: return@Episodes
                            context.startActivity(AnimePlayerActivity.intent(context, sourceId, malId, episode))
                        },
                        onDownload = viewModel::download,
                        onDelete = viewModel::deleteDownload,
                        onRetry = viewModel::loadEpisodes,
                    )
                }
            }
        }
    }

    @Composable
    private fun ChooseSource(
        viewModel: AnimeWatchViewModel,
        state: AnimeWatchViewModel.State,
        sources: List<eu.kanade.tachiyomi.animesource.AnimeSource>,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.forEach { source ->
                    FilterChip(
                        selected = state.source?.id == source.id,
                        onClick = { viewModel.chooseSource(source) },
                        label = { Text("${source.name} (${source.lang})") },
                    )
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = viewModel::search) { Icon(MaterialSymbols.Rounded.Search, null) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            )
            when {
                state.searching -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                state.searchError -> EmptyScreen(stringRes = MR.strings.anime_watch_search_error)
                state.results.isEmpty() -> EmptyScreen(stringRes = MR.strings.no_results_found)
                else -> LazyColumn {
                    items(state.results) { anime ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.pick(anime) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MangaCover.Book(data = anime.thumbnail_url, modifier = Modifier.width(52.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(anime.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Episodes(
        state: AnimeWatchViewModel.State,
        downloads: Map<String, AnimeDownloads.Item>,
        onPlay: (SEpisode) -> Unit,
        onDownload: (SEpisode) -> Unit,
        onDelete: (SEpisode) -> Unit,
        onRetry: () -> Unit,
    ) {
        when {
            state.episodesLoading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            state.episodesError -> EmptyScreen(
                stringRes = MR.strings.anime_watch_episodes_error,
                actions = listOf(EmptyScreenAction(MR.strings.action_retry, MaterialSymbols.Rounded.Refresh, onRetry)),
            )
            state.episodes.isEmpty() -> EmptyScreen(stringRes = MR.strings.anime_watch_no_episodes)
            else -> LazyColumn {
                items(state.episodes, key = { it.url }) { episode ->
                    val watched = episode.episode_number > 0 &&
                        episode.episode_number <= (state.anime?.episodesWatched ?: 0)
                    val download = downloads[episode.url]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(episode) }
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = episode.name.ifBlank {
                                    stringResource(MR.strings.anime_watch_episode, episodeLabel(episode.episode_number))
                                },
                                color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!episode.scanlator.isNullOrBlank()) {
                                Text(
                                    episode.scanlator!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (watched) {
                            Icon(
                                MaterialSymbols.RoundedFilled.CheckCircle,
                                stringResource(MR.strings.anime_watch_watched),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        when (download?.state) {
                            Download.STATE_COMPLETED -> IconButton(onClick = { onDelete(episode) }) {
                                Icon(MaterialSymbols.Rounded.Delete, stringResource(MR.strings.anime_download_delete))
                            }
                            Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING ->
                                IconButton(onClick = { onDelete(episode) }) {
                                    if (download.percent > 0) {
                                        CircularProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.size(24.dp))
                                    } else {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            else -> IconButton(onClick = { onDownload(episode) }) {
                                Icon(MaterialSymbols.Rounded.Download, stringResource(MR.strings.anime_download))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun episodeLabel(number: Float): String =
        if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()
}
