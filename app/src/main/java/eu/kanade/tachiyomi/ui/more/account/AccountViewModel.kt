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
import eu.kanade.tachiyomi.data.account.supabase
import eu.kanade.tachiyomi.util.system.toast
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val getChaptersByMangaId: tachiyomi.domain.chapter.interactor.GetChaptersByMangaId,
    private val updateManga: eu.kanade.domain.manga.interactor.UpdateManga,
    private val librarySupabaseRepository: LibrarySupabaseRepository,
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences,
    private val updateMangaFromRemote: mihon.domain.source.interactor.UpdateMangaFromRemote,
    private val updateChapter: tachiyomi.domain.chapter.interactor.UpdateChapter,
    private val chapterRepository: tachiyomi.domain.chapter.repository.ChapterRepository,
) : ViewModel() {

    private val syncMutex = Mutex()

    val state: StateFlow<State> = accountRepository.sessionFlow
        .map { State(username = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), State(isLoading = true))

    init {
        refreshAccount()
    }

    fun refreshAccount() {
        viewModelScope.launchIO {
            accountRepository.fetchUsername()

            // AOI: Automatic initial progress backfill
            val user = supabase.auth.currentUserOrNull()
            if (user != null && libraryPreferences.lastFullProgressSyncUserId.get() != user.id) {
                if (syncMutex.tryLock()) {
                    try {
                        logcat(LogPriority.INFO) { "Sync: Detected new user session, starting automatic backfill" }
                        val localFavorites = getLibraryManga.await()
                        val success = librarySupabaseRepository.backfillAllProgress(
                            mangaList = localFavorites.map { it.manga },
                            getChapters = getChaptersByMangaId,
                        )
                        if (success) {
                            libraryPreferences.lastFullProgressSyncUserId.set(user.id)
                            logcat(LogPriority.INFO) { "Sync: Automatic backfill finished and marked as done" }
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Sync: Automatic backfill failed" }
                    } finally {
                        syncMutex.unlock()
                    }
                } else {
                    logcat(LogPriority.DEBUG) { "Sync: Backfill already in progress, skipping concurrent call" }
                }
            }
        }
    }

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
            syncMutex.withLock {
                try {
                    logcat(LogPriority.INFO) { "Sync: Reconciliation starting for user ${user.id}" }
                    val localFavorites = getLibraryManga.await().map { it.manga }
                    logcat(LogPriority.INFO) { "Sync: Found ${localFavorites.size} local favorites" }

                    librarySupabaseRepository.reconcileLocalToCloud(
                        userId = user.id,
                        localMangaList = localFavorites,
                        getChapters = getChaptersByMangaId,
                    )

                    logcat(LogPriority.INFO) { "Sync: Full update completed successfully" }
                    viewModelScope.launch { context.toast("Upload completed!") }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Sync: Upload failed" }
                    viewModelScope.launch { context.toast("Upload failed: ${e.message}") }
                }
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
            syncMutex.withLock {
                try {
                    logcat(LogPriority.INFO) { "Sync: Import starting for user ${user.id}" }
                    librarySupabaseRepository.reconcileCloudToLocal(
                        userId = user.id,
                        updateMangaFromRemote = updateMangaFromRemote,
                        updateChapter = updateChapter,
                        chapterRepository = chapterRepository,
                    )
                    logcat(LogPriority.INFO) { "Sync: Import completed successfully" }
                    viewModelScope.launch { context.toast("Import completed!") }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Sync: Import failed" }
                    viewModelScope.launch { context.toast("Import failed: ${e.message}") }
                }
            }
        }
    }

    @Immutable
    data class State(
        val username: String? = null,
        val isLoading: Boolean = false,
    )
}
