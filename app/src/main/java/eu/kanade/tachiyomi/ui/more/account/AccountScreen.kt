package eu.kanade.tachiyomi.ui.more.account

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.account.AccountScreenContent
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class AccountScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        val viewModel = metroViewModel<AccountViewModel>()
        val state by viewModel.state.collectAsState()
        val syncState by viewModel.syncState.collectAsState()

        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refreshAccount()
            }
        }

        var showFullResyncDialog by remember { mutableStateOf(false) }

        AccountScreenContent(
            state = state,
            syncState = syncState,
            onNavigateBack = navigator::pop,
            onNavigateToCloudLibrary = { uriHandler.openUri(CLOUD_LIBRARY_URL) },
            onFullResync = { showFullResyncDialog = true },
            onRemoveLocalOnly = viewModel::removeLocalOnly,
            onKeepLocalOnly = viewModel::keepLocalOnly,
            onLogin = viewModel::login,
            onSignUp = viewModel::signUp,
            onLogout = viewModel::logout,
        )

        if (showFullResyncDialog) {
            AlertDialog(
                onDismissRequest = { showFullResyncDialog = false },
                title = { Text(stringResource(MR.strings.cloud_sync_full_resync)) },
                text = { Text(stringResource(MR.strings.cloud_sync_full_resync_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showFullResyncDialog = false
                            viewModel.fullResync()
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFullResyncDialog = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }

    private companion object {
        const val CLOUD_LIBRARY_URL = "https://aoi-mangas.vercel.app/"
    }
}
