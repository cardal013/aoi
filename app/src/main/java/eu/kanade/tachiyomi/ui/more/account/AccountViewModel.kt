package eu.kanade.tachiyomi.ui.more.account

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.account.AccountRepository
import eu.kanade.tachiyomi.data.account.CloudSync
import eu.kanade.tachiyomi.util.system.toast
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import java.io.IOException

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AccountViewModel(
    private val context: Context,
    private val accountRepository: AccountRepository,
    private val cloudSync: CloudSync,
) : ViewModel() {

    val state: StateFlow<State> = accountRepository.sessionFlow
        .map { State(username = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), State(isLoading = true))

    val syncState: StateFlow<CloudSync.State> = cloudSync.state

    fun refreshAccount() {
        viewModelScope.launchIO { accountRepository.fetchUsername() }
    }

    fun login(username: String, password: String) {
        viewModelScope.launchIO {
            accountRepository.login(username, password).onFailure { showAuthError(it) }
        }
    }

    fun signUp(username: String, password: String) {
        viewModelScope.launchIO {
            accountRepository.signUp(username, password).onFailure { showAuthError(it) }
        }
    }

    private suspend fun showAuthError(e: Throwable) {
        val message = when {
            e is AuthRestException && e.errorCode == AuthErrorCode.InvalidCredentials ->
                context.stringResource(MR.strings.account_error_credentials)
            e is AuthRestException &&
                (e.errorCode == AuthErrorCode.UserAlreadyExists || e.errorCode == AuthErrorCode.EmailExists) ->
                context.stringResource(MR.strings.account_error_username_taken)
            e is AuthRestException -> e.errorDescription.ifBlank { null } ?: e.message
            e is IOException || e is HttpRequestException || e.message?.contains("Unable to resolve host") == true ->
                context.stringResource(MR.strings.account_error_no_internet)
            else -> e.message
        } ?: context.stringResource(MR.strings.account_error_unknown)
        withUIContext { context.toast(message) }
    }

    fun logout() {
        viewModelScope.launchIO { accountRepository.logout() }
    }

    fun syncNow() = cloudSync.syncNow()

    fun fullResync() = cloudSync.fullResync()

    fun removeLocalOnly() = cloudSync.removeLocalOnly()

    fun keepLocalOnly() = cloudSync.keepLocalOnly()

    @Immutable
    data class State(
        val username: String? = null,
        val isLoading: Boolean = false,
    )
}
