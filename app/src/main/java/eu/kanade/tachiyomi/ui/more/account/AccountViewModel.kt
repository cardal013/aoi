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
import eu.kanade.tachiyomi.data.account.LibrarySupabaseRepository
import eu.kanade.tachiyomi.util.system.toast
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AccountViewModel(
    private val context: Context,
    private val accountRepository: AccountRepository,
    private val mangaRepository: MangaRepository,
    private val getLibraryManga: GetLibraryManga,
    private val updateManga: eu.kanade.domain.manga.interactor.UpdateManga,
    private val librarySupabaseRepository: LibrarySupabaseRepository,
    private val supabase: SupabaseClient,
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

    fun uploadToCloud() {
        val user = supabase.auth.currentUserOrNull() ?: run {
            logcat(LogPriority.WARN) { "Sync: Cannot upload, no user session" }
            return
        }
        viewModelScope.launch {
            context.toast("Upload started...")
        }
        viewModelScope.launchIO {
            try {
                logcat(LogPriority.INFO) { "Sync: Upload starting for user ${user.id}" }
                val localFavorites = getLibraryManga.await()
                logcat(LogPriority.INFO) { "Sync: Found ${localFavorites.size} local favorites" }

                librarySupabaseRepository.deleteAllLibrary(user.id)
                logcat(LogPriority.INFO) { "Sync: Remote library cleared" }

                localFavorites.forEach { item ->
                    logcat(LogPriority.DEBUG) { "Sync: Uploading ${item.manga.title}" }
                    librarySupabaseRepository.uploadMangaSync(user.id, item.manga)
                }
                logcat(LogPriority.INFO) { "Sync: Upload completed successfully" }
                viewModelScope.launch { context.toast("Upload completed!") }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: Upload failed" }
                viewModelScope.launch { context.toast("Upload failed: ${e.message}") }
            }
        }
    }

    fun importFromCloud() {
        val user = supabase.auth.currentUserOrNull() ?: run {
            logcat(LogPriority.WARN) { "Sync: Cannot import, no user session" }
            return
        }
        viewModelScope.launch {
            context.toast("Import started...")
        }
        viewModelScope.launchIO {
            try {
                logcat(LogPriority.INFO) { "Sync: Import starting for user ${user.id}" }
                librarySupabaseRepository.getUserLibrary(user.id).onSuccess { remoteItems ->
                    logcat(LogPriority.INFO) { "Sync: Found ${remoteItems.size} remote items" }

                    val initialLocal = getLibraryManga.await()
                    val remoteMangaUrls = remoteItems.map { it.mangaUrl }.toSet()

                    // 1. Restore remote items first (safe)
                    var imported = 0
                    var failed = 0
                    remoteItems.forEach { remote ->
                        try {
                            logcat(LogPriority.DEBUG) { "Sync: Restoring ${remote.title}" }
                            librarySupabaseRepository.restoreRemoteMangaLocally(remote)
                            imported++
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Sync: Failed to restore ${remote.title}" }
                            failed++
                        }
                    }

                    // 2. Only after restore, remove favorites that are NOT in the cloud
                    val toUnfavorite = initialLocal
                        .filter { it.manga.url !in remoteMangaUrls }
                        .map { MangaUpdate(id = it.manga.id, favorite = false) }

                    if (toUnfavorite.isNotEmpty()) {
                        updateManga.awaitAll(toUnfavorite)
                        logcat(LogPriority.INFO) { "Sync: Removed ${toUnfavorite.size} local-only favorites" }
                    }

                    logcat(LogPriority.INFO) { "Sync: Import completed. Imported: $imported, Failed: $failed" }
                    viewModelScope.launch { context.toast("Import completed! ($imported success, $failed fail)") }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync: Import failed" }
                viewModelScope.launch { context.toast("Import failed: ${e.message}") }
            }
        }
    }

    @Immutable
    data class State(
        val email: String? = null,
        val isLoading: Boolean = false,
    )
}
