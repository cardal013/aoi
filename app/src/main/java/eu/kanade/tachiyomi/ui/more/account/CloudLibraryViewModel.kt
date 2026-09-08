package eu.kanade.tachiyomi.ui.more.account

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.account.LibrarySupabaseRepository
import eu.kanade.tachiyomi.data.account.UserLibraryItem
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CloudLibraryViewModel(
    private val supabase: SupabaseClient,
    private val librarySupabaseRepository: LibrarySupabaseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        fetchLibrary()
    }

    fun fetchLibrary() {
        val user = supabase.auth.currentUserOrNull()
        if (user == null) {
            _state.update { it.copy(isLoading = false, error = "Unauthorized") }
            return
        }

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launchIO {
            librarySupabaseRepository.getUserLibrary(user.id)
                .onSuccess { items ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            itemsByStatus = librarySupabaseRepository.groupByStatus(items)
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = error.message ?: "Unknown error") }
                }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val itemsByStatus: Map<String, List<UserLibraryItem>> = emptyMap(),
        val error: String? = null,
    )
}
