package eu.kanade.tachiyomi.ui.more.account

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.account.AccountScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.more.account.AccountViewModel
import eu.kanade.tachiyomi.ui.more.account.CloudLibraryScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Download

class AccountScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<AccountViewModel>()
        val state by viewModel.state.collectAsState()

        var showUpdateDialog by remember { mutableStateOf(false) }
        var syncType by remember { mutableStateOf<SyncType?>(null) }

        AccountScreenContent(
            state = state,
            onNavigateBack = navigator::pop,
            onNavigateToCloudLibrary = { navigator.push(CloudLibraryScreen()) },
            onUpdateAccount = { showUpdateDialog = true },
            onLogin = viewModel::login,
            onSignUp = viewModel::signUp,
            onLogout = viewModel::logout,
        )

        if (showUpdateDialog) {
            SyncOptionsBottomSheet(
                onDismiss = { showUpdateDialog = false },
                onOptionSelected = {
                    syncType = it
                    showUpdateDialog = false
                }
            )
        }

        syncType?.let { type ->
            SyncConfirmationDialog(
                type = type,
                onDismiss = { syncType = null },
                onConfirm = {
                    if (type == SyncType.IMPORT) viewModel.importFromCloud() else viewModel.uploadToCloud()
                    syncType = null
                }
            )
        }
    }

    @Composable
    private fun SyncOptionsBottomSheet(onDismiss: () -> Unit, onOptionSelected: (SyncType) -> Unit) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Sync Library",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Choose a direction to sync your library",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

                Spacer(modifier = Modifier.height(24.dp))

                SyncOptionCard(
                    title = "Import from cloud",
                    subtitle = "Replaces local library with cloud version",
                    icon = MaterialSymbols.Rounded.Download,
                    onClick = { onOptionSelected(SyncType.IMPORT) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                SyncOptionCard(
                    title = "Upload to cloud",
                    subtitle = "Replaces cloud library with local version",
                    icon = MaterialSymbols.Rounded.ArrowUpward,
                    onClick = { onOptionSelected(SyncType.UPLOAD) }
                )
            }
        }
    }

    @Composable
    private fun SyncOptionCard(
        title: String,
        subtitle: String,
        icon: ImageVector,
        onClick: () -> Unit,
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }

    @Composable
    private fun SyncConfirmationDialog(type: SyncType, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        val message = if (type == SyncType.IMPORT) {
            "This will replace your local library with the cloud version. Mangas not present locally will be added. Any local manga not present in the cloud will be removed from your library. Continue?"
        } else {
            "This will replace your cloud library with your local library. Any cloud data not present locally will be lost. Continue?"
        }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Confirm Sync") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )
    }

    private enum class SyncType { IMPORT, UPLOAD }
}
