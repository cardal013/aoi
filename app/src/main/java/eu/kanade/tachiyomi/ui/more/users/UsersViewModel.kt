package eu.kanade.tachiyomi.ui.more.users

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.account.UsersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ecrã Users (só admins): pesquisa por username, dar/tirar admin e mudar a palavra-passe.
 * As regras (só admins; ninguém se tira admin a si próprio; não se muda a palavra-passe de outro admin)
 * são garantidas no servidor; aqui o ecrã só esconde o que não dá.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class UsersViewModel(
    private val usersRepository: UsersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(State(currentUserId = usersRepository.currentUserId()))
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Avisos para mostrar num toast. */
    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = mutableEvents.asSharedFlow()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launchIO {
            state.map { it.query.trim() }
                .distinctUntilChanged()
                .debounce(SEARCH_DEBOUNCE)
                .collectLatest(::load)
        }
    }

    fun onQueryChange(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun retry() {
        viewModelScope.launchIO { load(state.value.query.trim()) }
    }

    /** Lê as contas com [search] no username. */
    private suspend fun load(search: String) {
        mutableState.update { it.copy(isLoading = true, hasError = false) }
        try {
            val users = usersRepository.listUsers(search)
            mutableState.update { it.copy(users = users, isLoading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Users: a lista falhou" }
            mutableState.update { it.copy(isLoading = false, hasError = true) }
        }
    }

    /** Interruptor de Admin. Muda logo no ecrã e volta atrás se o servidor recusar. */
    fun setAdmin(user: UsersRepository.User, makeAdmin: Boolean) {
        replaceUser(user.copy(isAdmin = makeAdmin))
        viewModelScope.launchIO {
            try {
                usersRepository.setAdmin(user.userId, makeAdmin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Users: dar/tirar admin falhou" }
                replaceUser(user)
                mutableEvents.tryEmit(Event.AdminChangeFailed)
            }
        }
    }

    /** Muda a palavra-passe de [user] (já confirmada no diálogo). */
    fun setPassword(user: UsersRepository.User, newPassword: String) {
        viewModelScope.launchIO {
            try {
                usersRepository.setPassword(user.userId, newPassword)
                mutableEvents.tryEmit(Event.PasswordChanged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UsersRepository.UsersException) {
                logcat(LogPriority.WARN, e) { "Users: o servidor recusou a palavra-passe" }
                val event = if (e.errorCode == ERROR_TARGET_IS_ADMIN) {
                    Event.PasswordOfAdmin
                } else {
                    Event.PasswordChangeFailed
                }
                mutableEvents.tryEmit(event)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Users: mudar a palavra-passe falhou" }
                mutableEvents.tryEmit(Event.PasswordChangeFailed)
            }
        }
    }

    private fun replaceUser(changedUser: UsersRepository.User) {
        mutableState.update { currentState ->
            val users = currentState.users.map { if (it.userId == changedUser.userId) changedUser else it }
            currentState.copy(users = users)
        }
    }

    enum class Event {
        PasswordChanged,
        PasswordChangeFailed,
        PasswordOfAdmin,
        AdminChangeFailed,
    }

    @Immutable
    data class State(
        val currentUserId: String?,
        val query: String = "",
        val users: List<UsersRepository.User> = emptyList(),
        val isLoading: Boolean = true,
        val hasError: Boolean = false,
    )

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
        private const val ERROR_TARGET_IS_ADMIN = "target_is_admin"
        private val SEARCH_DEBOUNCE = 300.milliseconds
    }
}
