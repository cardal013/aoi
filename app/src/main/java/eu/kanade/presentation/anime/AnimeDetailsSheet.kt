package eu.kanade.presentation.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailsSheet(
    anime: Anime,
    onDismissRequest: () -> Unit,
    onChangeStatus: (AnimeStatus) -> Unit,
    onChangeEpisodes: (Long) -> Unit,
    onRemove: () -> Unit,
    onOpenAniList: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                MangaCover.Book(data = anime.coverUrl, modifier = Modifier.width(88.dp))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = anime.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    anime.titleEnglish?.takeIf { it != anime.title }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = animeInfoLine(anime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AnimeStatus.entries.forEachIndexed { index, status ->
                    SegmentedButton(
                        selected = anime.status == status,
                        onClick = { onChangeStatus(status) },
                        shape = SegmentedButtonDefaults.itemShape(index, AnimeStatus.entries.size),
                        label = { Text(status.label(), maxLines = 1) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        MR.strings.anime_episodes_watched,
                        anime.episodesWatched.toInt(),
                        anime.episodes?.toString() ?: "?",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = { onChangeEpisodes(anime.episodesWatched - 1) },
                    enabled = anime.episodesWatched > 0,
                ) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = { onChangeEpisodes(anime.episodesWatched + 1) },
                    enabled = anime.episodesWatched < (anime.episodes ?: Long.MAX_VALUE),
                ) {
                    Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onOpenAniList) {
                    Icon(imageVector = MaterialSymbols.AutoMirroredRounded.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(MR.strings.anime_open_anilist))
                }
                TextButton(onClick = onRemove) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(MR.strings.anime_remove), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** "TV · 2023 · 28 eps", só com o que o AniList souber. */
internal fun animeInfoLine(anime: Anime): String = listOfNotNull(
    anime.format?.replace('_', ' '),
    anime.seasonYear?.toString(),
    anime.episodes?.let { "$it eps" },
).joinToString(" · ")
