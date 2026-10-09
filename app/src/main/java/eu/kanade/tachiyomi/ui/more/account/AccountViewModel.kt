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
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
) : ViewModel() {

    private val syncMutex = Mutex()

    val state: StateFlow<State> = accountRepository.sessionFlow
        .map { State(username = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), State(isLoading = true))

    private val mutableSyncState = MutableStateFlow(SyncState())
    val syncState = mutableSyncState.asStateFlow()

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
                        mutableSyncState.update { it.copy(status = SyncStatus.Syncing, progress = null, failedMangas = emptyList()) }
                        logcat(LogPriority.INFO) { "Sync: Detected new user session, starting automatic backfill" }

                        val localFavorites = getLibraryManga.await().map { it.manga }
                        val success = librarySupabaseRepository.backfillAllProgress(
                            mangaList = localFavorites,
                            getChapters = getChaptersByMangaId,
                            onProgress = { current, total ->
                                mutableSyncState.update { it.copy(progress = current to total) }
                            }
                        )
                        if (success) {
                            libraryPreferences.lastFullProgressSyncUserId.set(user.id)
                            mutableSyncState.update { it.copy(status = SyncStatus.Success) }
                            logcat(LogPriority.INFO) { "Sync: Automatic backfill finished and marked as done" }
                            delay(1000)
                            mutableSyncState.update { it.copy(status = SyncStatus.Idle) }
                        } else {
                            mutableSyncState.update { it.copy(status = SyncStatus.Error) }
                            logcat(LogPriority.WARN) { "Sync: Automatic backfill finished with errors" }
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Sync: Automatic backfill failed" }
                        mutableSyncState.update { it.copy(status = SyncStatus.Error) }
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
            accountRepository.login(email, password).onFailure { e ->
                val message = getAuthErrorMessage(e)
                viewModelScope.launch { context.toast(message) }
            }
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launchIO {
            accountRepository.signUp(email, password).onFailure { e ->
                val message = getAuthErrorMessage(e)
                viewModelScope.launch { context.toast(message) }
            }
        }
    }

    private fun getAuthErrorMessage(e: Throwable): String {
        return when (e) {
            is AuthRestException -> {
                when (e.errorCode) {
                    AuthErrorCode.InvalidCredentials -> "Password ou email incorretos"
                    AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists -> "Este email já tem conta"
                    else -> e.errorDescription.takeIf { it.isNotBlank() } ?: e.message ?: "Erro na autenticação"
                }
            }
            is java.io.IOException -> "Sem ligação à internet"
            else -> if (e.message?.contains("Unable to resolve host") == true) {
                "Sem ligação à internet"
            } else {
                e.message ?: "Ocorreu um erro inesperado"
            }
        }
    }

    fun logout() {
        viewModelScope.launchIO {
            accountRepository.logout()
        }
    }

    /**
     * Envia as alterações locais para a cloud.
     * Incremental por defeito (só o que mudou desde o último upload com sucesso);
     * completo no primeiro sync do utilizador ou com [fullResync].
     */
    fun uploadToCloud(fullResync: Boolean = false) {
        val user = supabase.auth.currentUserOrNull() ?: run {
            logcat(LogPriority.WARN) { "Sync: Cannot upload, no user session" }
            return
        }

        mutableSyncState.update { it.copy(status = SyncStatus.Syncing, progress = null, failedMangas = emptyList()) }

        viewModelScope.launchIO {
            syncMutex.withLock {
                try {
                    val syncStart = System.currentTimeMillis()
                    val lastUploadSync = libraryPreferences.lastCloudUploadSync(user.id)
                    val since = if (fullResync) 0L else lastUploadSync.get()
                    val onProgress = { current: Int, total: Int ->
                        mutableSyncState.update { it.copy(progress = current to total) }
                    }
                    val onMangaFailed = { title: String ->
                        mutableSyncState.update { it.copy(failedMangas = it.failedMangas + title) }
                    }

                    logcat(LogPriority.INFO) {
                        "Sync: Upload starting for user ${user.id} | ${if (since == 0L) "completo" else "incremental"}"
                    }
                    val result = if (since == 0L) {
                        val localFavorites = getLibraryManga.await().map { it.manga }
                        logcat(LogPriority.INFO) { "Sync: Found ${localFavorites.size} local favorites" }
                        librarySupabaseRepository.reconcileLocalToCloud(
                            userId = user.id,
                            localMangaList = localFavorites,
                            getChapters = getChaptersByMangaId,
                            onProgress = onProgress,
                            onMangaFailed = onMangaFailed,
                        )
                    } else {
                        librarySupabaseRepository.syncLocalChangesToCloud(
                            userId = user.id,
                            since = since,
                            onProgress = onProgress,
                            onMangaFailed = onMangaFailed,
                        )
                    }

                    val failedCount = mutableSyncState.value.failedMangas.size
                    if (result.ok && failedCount == 0) {
                        // Só avança o marco do último sync se tudo correu bem
                        lastUploadSync.set(syncStart)
                        logcat(LogPriority.INFO) { "Sync: Upload completed | ${result.summary()}" }
                        mutableSyncState.update { it.copy(status = SyncStatus.Success) }
                        viewModelScope.launch { context.toast("Upload completed! ${result.totalSent} records sent") }
                        delay(1000)
                        mutableSyncState.update { it.copy(status = SyncStatus.Idle) }
                    } else {
                        logcat(LogPriority.WARN) { "Sync: Upload finished with errors | ${result.summary()}" }
                        mutableSyncState.update { it.copy(status = SyncStatus.Error) }
                        viewModelScope.launch {
                            context.toast(
                                if (failedCount > 0) "Upload finished with $failedCount errors" else "Upload finished with errors",
                            )
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Sync: Upload failed" }
                    mutableSyncState.update { it.copy(status = SyncStatus.Error) }
                    viewModelScope.launch { context.toast("Upload failed: ${e.message}") }
                }
            }
        }
    }

    /**
     * Traz da cloud o que mudou desde o último import com sucesso (completo no primeiro).
     */
    fun importFromCloud() {
        val user = supabase.auth.currentUserOrNull() ?: run {
            logcat(LogPriority.WARN) { "Sync: Cannot import, no user session" }
            return
        }

        mutableSyncState.update { it.copy(status = SyncStatus.Syncing, progress = null, failedMangas = emptyList()) }

        viewModelScope.launchIO {
            syncMutex.withLock {
                try {
                    val syncStart = System.currentTimeMillis()
                    val lastDownloadSync = libraryPreferences.lastCloudDownloadSync(user.id)
                    logcat(LogPriority.INFO) { "Sync: Import starting for user ${user.id}" }
                    val result = librarySupabaseRepository.reconcileCloudToLocal(
                        userId = user.id,
                        since = lastDownloadSync.get(),
                        pendingSince = libraryPreferences.lastCloudUploadSync(user.id).get(),
                        updateChapter = updateChapter,
                        onProgress = { current: Int, total: Int ->
                            mutableSyncState.update { it.copy(progress = current to total) }
                        },
                        onMangaFailed = { title: String ->
                            mutableSyncState.update { it.copy(failedMangas = it.failedMangas + title) }
                        }
                    )

                    val failedCount = mutableSyncState.value.failedMangas.size
                    if (result.ok && failedCount == 0) {
                        lastDownloadSync.set(syncStart)
                        logcat(LogPriority.INFO) { "Sync: Import completed | ${result.summary()}" }
                        mutableSyncState.update { it.copy(status = SyncStatus.Success) }
                        viewModelScope.launch { context.toast("Import completed! ${result.totalReceived} records received") }
                        delay(1000)
                        mutableSyncState.update { it.copy(status = SyncStatus.Idle) }
                    } else {
                        logcat(LogPriority.WARN) { "Sync: Import finished with errors | ${result.summary()}" }
                        mutableSyncState.update { it.copy(status = SyncStatus.Error) }
                        viewModelScope.launch {
                            context.toast(
                                if (failedCount > 0) "Import finished with $failedCount errors" else "Import finished with errors",
                            )
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Sync: Import failed" }
                    mutableSyncState.update { it.copy(status = SyncStatus.Error) }
                    viewModelScope.launch { context.toast("Import failed: ${e.message}") }
                }
            }
        }
    }

    fun clearFailedMangas() {
        mutableSyncState.update { it.copy(failedMangas = emptyList(), status = SyncStatus.Idle) }
    }

    @Immutable
    data class State(
        val username: String? = null,
        val isLoading: Boolean = false,
    )

    @Immutable
    data class SyncState(
        val status: SyncStatus = SyncStatus.Idle,
        val progress: Pair<Int, Int>? = null,
        val failedMangas: List<String> = emptyList(),
    )

    enum class SyncStatus { Idle, Syncing, Success, Error }
}
