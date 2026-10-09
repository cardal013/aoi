package eu.kanade.tachiyomi.ui.more.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.account.AccountScreenContent
import eu.kanade.presentation.util.Screen

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

        AccountScreenContent(
            state = state,
            syncState = syncState,
            onNavigateBack = navigator::pop,
            onNavigateToCloudLibrary = { uriHandler.openUri(CLOUD_LIBRARY_URL) },
            onRemoveLocalOnly = viewModel::removeLocalOnly,
            onKeepLocalOnly = viewModel::keepLocalOnly,
            onLogin = viewModel::login,
            onSignUp = viewModel::signUp,
            onLogout = viewModel::logout,
        )
    }

    private companion object {
        const val CLOUD_LIBRARY_URL = "https://aoi-mangas.vercel.app/"
    }
}
