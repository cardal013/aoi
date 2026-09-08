package eu.kanade.tachiyomi.ui.more.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.account.AccountScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.more.account.AccountViewModel
import eu.kanade.tachiyomi.ui.more.account.CloudLibraryScreen

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
            SyncOptionsDialog(
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
    private fun SyncOptionsDialog(onDismiss: () -> Unit, onOptionSelected: (SyncType) -> Unit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update Account") },
            text = {
                Column {
                    TextButton(onClick = { onOptionSelected(SyncType.IMPORT) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Import from cloud")
                    }
                    TextButton(onClick = { onOptionSelected(SyncType.UPLOAD) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Upload to cloud")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )
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
