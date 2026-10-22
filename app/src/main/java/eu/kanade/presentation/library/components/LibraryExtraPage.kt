package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/** Página a seguir às categorias da biblioteca (no Aoi, a dos animes). */
@Immutable
class LibraryExtraPage(
    val title: String,
    val count: Int?,
    val content: @Composable (contentPadding: PaddingValues) -> Unit,
)
