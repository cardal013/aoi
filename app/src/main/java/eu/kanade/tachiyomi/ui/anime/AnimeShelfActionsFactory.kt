package eu.kanade.tachiyomi.ui.anime

import eu.kanade.presentation.anime.AnimeShelfActions

/** Liga os menus e diálogos das pastas (presentation) às ações do [AnimeListViewModel]. */
fun AnimeListViewModel.shelfActions(): AnimeShelfActions = AnimeShelfActions(
    onCreateFolder = ::createFolder,
    onMoveToFolder = ::moveToFolder,
    onRemoveFromFolder = ::removeFromFolder,
    onRenameFolder = ::renameFolder,
    onSetFolderCover = ::setFolderCover,
    onToggleFolderSortOrder = ::toggleFolderSortOrder,
    onDeleteFolder = ::deleteFolder,
)
