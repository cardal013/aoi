package eu.kanade.presentation.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

// Transparência da capa enquanto é arrastada
private const val DRAGGING_ALPHA = 0.7f

/**
 * Grelha de capas da aba Anime e do ecrã da pasta.
 * Tocar chama [onClick]. Carregar sem largar e mexer arrasta (se [dragEnabled]); ao largar chama
 * [onReordered] com a ordem nova e a chave do arrastado. Carregar sem largar e largar sem mexer chama [onMenu].
 * [key] tem de ser única por entrada.
 */
@Composable
fun <T> ReorderableCoverGrid(
    items: List<T>,
    key: (T) -> String,
    dragEnabled: Boolean,
    contentPadding: PaddingValues,
    onClick: (T) -> Unit,
    onMenu: (T) -> Unit,
    onReordered: (orderedKeys: List<String>, movedKey: String) -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    // Ordem no ecrã: durante um arrastar muda aqui primeiro e só no fim vai para o ViewModel
    var orderedItems by remember { mutableStateOf(items) }
    var keysBeforeDrag by remember { mutableStateOf<List<String>?>(null) }

    // Fora de um arrastar, segue a lista que vem do ViewModel
    LaunchedEffect(items) {
        if (keysBeforeDrag == null) {
            orderedItems = items
        }
    }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        if (dragEnabled) {
            orderedItems = orderedItems.toMutableList().apply { add(to.index, removeAt(from.index)) }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(orderedItems, key = key) { item ->
            val itemKey = key(item)
            ReorderableItem(reorderState, key = itemKey) { isDragging ->
                // O clickable fica por fora do arrastar: assim, largar depois de carregar sem largar
                // não conta também como toque (o arrastar fica com esse "largar")
                Box(
                    modifier = Modifier
                        .alpha(if (isDragging) DRAGGING_ALPHA else 1f)
                        .clickable { onClick(item) }
                        .longPressDraggableHandle(
                            onDragStarted = { keysBeforeDrag = orderedItems.map(key) },
                            onDragStopped = {
                                val keysAfterDrag = orderedItems.map(key)
                                val orderChanged = keysAfterDrag != keysBeforeDrag
                                keysBeforeDrag = null
                                if (orderChanged) {
                                    onReordered(keysAfterDrag, itemKey)
                                } else {
                                    onMenu(item)
                                }
                            },
                        ),
                ) {
                    itemContent(item)
                }
            }
        }
    }
}
