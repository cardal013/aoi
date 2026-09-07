package eu.kanade.tachiyomi.ui.more.account

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.account.AccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AccountViewModel(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    val state: StateFlow<State> = accountRepository.sessionFlow
        .map { State(email = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), State(isLoading = true))

    fun login(email: String, password: String) {
        viewModelScope.launchIO {
            accountRepository.login(email, password)
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launchIO {
            accountRepository.signUp(email, password)
        }
    }

    fun logout() {
        viewModelScope.launchIO {
            accountRepository.logout()
        }
    }

    @Immutable
    data class State(
        val email: String? = null,
        val isLoading: Boolean = false,
    )
}
