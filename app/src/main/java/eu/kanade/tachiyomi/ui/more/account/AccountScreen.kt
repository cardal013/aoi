package eu.kanade.tachiyomi.ui.more.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.account.AccountScreenContent
import eu.kanade.presentation.util.Screen

class AccountScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<AccountViewModel>()
        val state by viewModel.state.collectAsState()

        AccountScreenContent(
            state = state,
            onNavigateBack = navigator::pop,
            onLogin = viewModel::login,
            onSignUp = viewModel::signUp,
            onLogout = viewModel::logout,
        )
    }
}
