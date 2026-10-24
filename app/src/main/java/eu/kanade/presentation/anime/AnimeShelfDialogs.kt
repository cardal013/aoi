package eu.kanade.presentation.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import tachiyomi.domain.anime.AnimeShelf
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeFolder
import tachiyomi.domain.anime.model.FolderSortOrder
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Menus e diálogos das pastas de animes, partilhados pela aba Anime e pelo ecrã da pasta.
 * Abrem-se ao carregar sem largar e largar sem mexer (carregar e mexer arrasta).
 */

/** O que a aba ou o ecrã da pasta pode fazer às pastas (ligado ao AnimeListViewModel). */
class AnimeShelfActions(
    val onCreateFolder: (name: String, anime: Anime) -> Unit,
    val onMoveToFolder: (anime: Anime, folderId: String) -> Unit,
    val onRemoveFromFolder: (anime: Anime) -> Unit,
    val onRenameFolder: (folderId: String, name: String) -> Unit,
    val onSetFolderCover: (folderId: String, malId: Long) -> Unit,
    val onToggleFolderSortOrder: (folder: AnimeFolder) -> Unit,
    val onDeleteFolder: (folderId: String) -> Unit,
)

/** Diálogo aberto de momento (null = nenhum). */
sealed interface ShelfDialog {
    data class AnimeMenu(val anime: Anime) : ShelfDialog
    data class FolderMenu(val folderId: String) : ShelfDialog
    data class CreateFolder(val anime: Anime) : ShelfDialog
    data class MoveToFolder(val anime: Anime) : ShelfDialog
    data class RenameFolder(val folderId: String) : ShelfDialog
    data class ChooseCover(val folderId: String) : ShelfDialog
    data class DeleteFolder(val folderId: String) : ShelfDialog
}

/**
 * Mostra o diálogo [dialog]. [folders] são todas as pastas (com os animes), para escolher
 * para onde mover e para os menus da pasta. [onChangeDialog] abre outro diálogo ou fecha (null).
 */
@Composable
fun AnimeShelfDialogs(
    dialog: ShelfDialog?,
    folders: List<AnimeShelf.Folder>,
    actions: AnimeShelfActions,
    onChangeDialog: (ShelfDialog?) -> Unit,
) {
    val close = { onChangeDialog(null) }
    when (dialog) {
        null -> Unit
        is ShelfDialog.AnimeMenu -> AnimeMenuDialog(
            anime = dialog.anime,
            hasFolders = folders.any { it.folder.id != dialog.anime.folderId },
            actions = actions,
            onChangeDialog = onChangeDialog,
        )
        is ShelfDialog.FolderMenu -> {
            val folder = folders.find { it.folder.id == dialog.folderId }
            if (folder == null) {
                // a pasta foi apagada entretanto (por exemplo, noutro aparelho)
                LaunchedEffect(Unit) { close() }
            } else {
                FolderMenuDialog(folder = folder.folder, actions = actions, onChangeDialog = onChangeDialog)
            }
        }
        is ShelfDialog.CreateFolder -> FolderNameDialog(
            title = stringResource(MR.strings.anime_folder_create),
            initialName = "",
            onDismissRequest = close,
            onConfirm = { name ->
                actions.onCreateFolder(name, dialog.anime)
                close()
            },
        )
        is ShelfDialog.MoveToFolder -> ChooseFolderDialog(
            folders = folders.filter { it.folder.id != dialog.anime.folderId },
            onDismissRequest = close,
            onPick = { folderId ->
                actions.onMoveToFolder(dialog.anime, folderId)
                close()
            },
        )
        is ShelfDialog.RenameFolder -> {
            val folder = folders.find { it.folder.id == dialog.folderId }?.folder
            FolderNameDialog(
                title = stringResource(MR.strings.anime_folder_rename),
                initialName = folder?.name.orEmpty(),
                onDismissRequest = close,
                onConfirm = { name ->
                    actions.onRenameFolder(dialog.folderId, name)
                    close()
                },
            )
        }
        is ShelfDialog.ChooseCover -> {
            val folder = folders.find { it.folder.id == dialog.folderId }
            ChooseCoverDialog(
                members = folder?.members.orEmpty(),
                currentCoverMalId = folder?.cover?.malId,
                onDismissRequest = close,
                onPick = { malId ->
                    actions.onSetFolderCover(dialog.folderId, malId)
                    close()
                },
            )
        }
        is ShelfDialog.DeleteFolder -> {
            val folder = folders.find { it.folder.id == dialog.folderId }?.folder
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(MR.strings.anime_folder_delete)) },
                text = { Text(stringResource(MR.strings.anime_folder_delete_confirm, folder?.name.orEmpty())) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            actions.onDeleteFolder(dialog.folderId)
                            close()
                        },
                    ) {
                        Text(stringResource(MR.strings.anime_folder_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = close) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}

/** Menu de um anime: mover para uma pasta, criar pasta, tirar da pasta (se estiver numa). */
@Composable
private fun AnimeMenuDialog(
    anime: Anime,
    hasFolders: Boolean,
    actions: AnimeShelfActions,
    onChangeDialog: (ShelfDialog?) -> Unit,
) {
    val menuItems = mutableListOf<Pair<String, () -> Unit>>()
    if (hasFolders) {
        val moveLabel = stringResource(MR.strings.anime_folder_move_to)
        menuItems += moveLabel to { onChangeDialog(ShelfDialog.MoveToFolder(anime)) }
    }
    val createLabel = stringResource(MR.strings.anime_folder_create)
    menuItems += createLabel to { onChangeDialog(ShelfDialog.CreateFolder(anime)) }
    if (anime.folderId != null) {
        menuItems += stringResource(MR.strings.anime_folder_remove_from) to {
            actions.onRemoveFromFolder(anime)
            onChangeDialog(null)
        }
    }
    MenuDialog(title = anime.title, menuItems = menuItems, onDismissRequest = { onChangeDialog(null) })
}

/** Menu de uma pasta: capa, nome, ordem e apagar. */
@Composable
private fun FolderMenuDialog(
    folder: AnimeFolder,
    actions: AnimeShelfActions,
    onChangeDialog: (ShelfDialog?) -> Unit,
) {
    val sortLabel = stringResource(MR.strings.anime_folder_sort, folder.sortOrder.label())
    val menuItems = listOf(
        stringResource(MR.strings.anime_folder_cover) to { onChangeDialog(ShelfDialog.ChooseCover(folder.id)) },
        stringResource(MR.strings.anime_folder_rename) to { onChangeDialog(ShelfDialog.RenameFolder(folder.id)) },
        sortLabel to {
            actions.onToggleFolderSortOrder(folder)
            onChangeDialog(null)
        },
        stringResource(MR.strings.anime_folder_delete) to { onChangeDialog(ShelfDialog.DeleteFolder(folder.id)) },
    )
    MenuDialog(title = folder.name, menuItems = menuItems, onDismissRequest = { onChangeDialog(null) })
}

/** Nome da ordem da pasta, para o menu e para o botão do ecrã da pasta. */
@Composable
fun FolderSortOrder.label(): String = stringResource(
    when (this) {
        FolderSortOrder.DATE_ASC -> MR.strings.anime_folder_sort_oldest
        FolderSortOrder.DATE_DESC -> MR.strings.anime_folder_sort_newest
        FolderSortOrder.MANUAL -> MR.strings.anime_folder_sort_manual
    },
)

/** Lista de opções num diálogo; tocar numa corre-a. */
@Composable
private fun MenuDialog(
    title: String,
    menuItems: List<Pair<String, () -> Unit>>,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                for ((label, onClick) in menuItems) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClick)
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/** Pede o nome de uma pasta (criar ou mudar o nome). Nome vazio não deixa confirmar. */
@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val hasName = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(MR.strings.anime_folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = hasName) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/** Escolher a pasta para onde vai o anime. */
@Composable
private fun ChooseFolderDialog(
    folders: List<AnimeShelf.Folder>,
    onDismissRequest: () -> Unit,
    onPick: (folderId: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.anime_folder_move_to)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(folders, key = { it.folder.id }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(folder.folder.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MangaCover.Book(data = folder.cover?.coverUrl, modifier = Modifier.width(40.dp))
                        Text(
                            text = "${folder.folder.name} · ${folder.members.size}",
                            modifier = Modifier.padding(start = 12.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/** Escolher a capa da pasta entre as temporadas que tem lá dentro. */
@Composable
private fun ChooseCoverDialog(
    members: List<Anime>,
    currentCoverMalId: Long?,
    onDismissRequest: () -> Unit,
    onPick: (malId: Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.anime_folder_cover)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(members, key = { it.malId }) { anime ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(anime.malId) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = anime.malId == currentCoverMalId, onClick = null)
                        MangaCover.Book(
                            data = anime.coverUrl,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .width(40.dp),
                        )
                        Text(
                            text = anime.title,
                            modifier = Modifier.padding(start = 12.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
