package eu.kanade.tachiyomi.data.account

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.toast
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

/**
 * Sync automático da biblioteca com a cloud, para a app toda:
 * - ao entrar na conta e ao abrir a app: import do que mudou na cloud e depois upload do que mudou localmente
 * - poucos segundos depois de cada alteração local (favoritos, estados, progresso): upload incremental
 * - quando volta a haver internet: import e upload, para enviar o que falhou sem rede
 * O primeiro sync de um utilizador num aparelho junta as duas bibliotecas e nunca apaga nada.
 */
@Inject
@SingleIn(AppScope::class)
class CloudSync(
    private val context: Context,
    private val repo: LibrarySupabaseRepository,
    private val mangaRepository: MangaRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val libraryPreferences: LibraryPreferences,
) {

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var scope: CoroutineScope? = null
    private var lastAutoSync = 0L

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            logcat(LogPriority.WARN) { "Sync: sem SUPABASE_URL (falta ignorance/local.properties), sync desligado" }
            return
        }
        this.scope = scope

        supabase.auth.sessionStatus
            .map { (it as? SessionStatus.Authenticated)?.session?.user?.id }
            .distinctUntilChanged()
            .onEach { userId ->
                mutableState.value = State(lastSyncAt = userId?.let(::lastSyncAt) ?: 0L)
                if (userId != null) sync(Mode.AUTO)
            }
            .launchIn(scope)

        mangaRepository.getLastLocalChangeAsFlow()
            .distinctUntilChanged()
            .drop(1)
            .debounce(UPLOAD_DEBOUNCE)
            .onEach { sync(Mode.UPLOAD) }
            .launchIn(scope)

        context.getSystemService(ConnectivityManager::class.java)
            ?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Também é chamado ao registar se já houver rede; o intervalo evita repetir o sync do arranque
                        if (System.currentTimeMillis() - lastAutoSync >= NETWORK_MIN_INTERVAL_MS) sync(Mode.AUTO)
                    }
                },
            )
    }

    fun onAppForeground() {
        if (System.currentTimeMillis() - lastAutoSync < FOREGROUND_MIN_INTERVAL_MS) return
        sync(Mode.AUTO)
    }

    /** Os mangas que já não estão na cloud saem também da biblioteca local. */
    fun removeLocalOnly() {
        val mangas = state.value.localOnly
        mutableState.update { it.copy(localOnly = emptyList()) }
        scope?.launchIO {
            mutex.withLock {
                mangaRepository.updateAll(mangas.map { MangaUpdate(id = it.id, favorite = false) })
                // Já não estão na cloud: não há remoção para enviar
                mangaRepository.clearCloudDirty(mangas.map { it.id })
            }
        }
    }

    /** Os mangas que já não estão na cloud ficam e voltam a ser enviados no próximo upload. */
    fun keepLocalOnly() {
        val mangas = state.value.localOnly
        mutableState.update { it.copy(localOnly = emptyList()) }
        scope?.launchIO { mangaRepository.markCloudDirty(mangas.map { it.id }) }
    }

    private fun sync(mode: Mode) {
        val scope = scope ?: return
        val userId = supabase.auth.currentUserOrNull()?.id ?: return
        if (mode == Mode.AUTO) lastAutoSync = System.currentTimeMillis()

        scope.launchIO {
            mutex.withLock {
                // Pode ter havido logout enquanto esperava pela vez
                if (supabase.auth.currentUserOrNull()?.id != userId) return@withLock
                try {
                    run(userId, mode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Sync: falhou ($mode)" }
                    mutableState.update { it.copy(running = false, progress = null) }
                }
            }
        }
    }

    private suspend fun run(userId: String, mode: Mode) {
        val start = System.currentTimeMillis()
        val uploadMarker = libraryPreferences.lastCloudUploadSync(userId)
        val downloadMarker = libraryPreferences.lastCloudDownloadSync(userId)
        val firstSync = uploadMarker.get() == 0L || downloadMarker.get() == 0L

        // O upload a seguir a uma alteração só precisa do import se for o primeiro sync
        val doImport = mode == Mode.AUTO || firstSync

        mutableState.update { it.copy(running = true, progress = null) }
        val onProgress = { current: Int, total: Int -> mutableState.update { it.copy(progress = current to total) } }

        var ok = true
        if (doImport) {
            val result = repo.reconcileCloudToLocal(
                userId = userId,
                since = if (firstSync) 0L else downloadMarker.get(),
                pendingSince = uploadMarker.get(),
                updateChapter = updateChapter,
                onProgress = onProgress,
            )
            if (result.ok) downloadMarker.set(start) else ok = false
            showLocalOnly(result.localOnly)
        }

        // No primeiro sync, enviar sem ter juntado o progresso da cloud podia fazer recuar capítulos lidos
        if (ok || !firstSync) {
            val result = if (firstSync) {
                repo.uploadWholeLibrary(
                    userId = userId,
                    localMangaList = mangaRepository.getFavorites(),
                    getChapters = getChaptersByMangaId,
                    onProgress = onProgress,
                )
            } else {
                repo.syncLocalChangesToCloud(
                    userId = userId,
                    since = uploadMarker.get(),
                    onProgress = onProgress,
                )
            }
            if (result.ok) {
                // Só avança o marco se tudo correu bem; o que falhou volta a ir no próximo
                uploadMarker.set(start)
                mangaRepository.clearCloudSyncedBefore(start)
            } else {
                ok = false
            }
        }

        mutableState.update {
            it.copy(
                running = false,
                progress = null,
                lastSyncAt = if (ok) start else it.lastSyncAt,
            )
        }
    }

    private suspend fun showLocalOnly(mangas: List<Manga>) {
        val before = state.value.localOnly.map { it.id }.toSet()
        mutableState.update { it.copy(localOnly = mangas) }
        if (mangas.isNotEmpty() && mangas.map { it.id }.toSet() != before) {
            withUIContext {
                context.toast(
                    context.pluralStringResource(MR.plurals.cloud_sync_local_only_toast, mangas.size, mangas.size),
                )
            }
        }
    }

    private fun lastSyncAt(userId: String): Long =
        maxOf(
            libraryPreferences.lastCloudUploadSync(userId).get(),
            libraryPreferences.lastCloudDownloadSync(userId).get(),
        )

    data class State(
        val running: Boolean = false,
        val progress: Pair<Int, Int>? = null,
        val lastSyncAt: Long = 0L,
        // Favoritos locais que já não estão na cloud, à espera de o utilizador decidir
        val localOnly: List<Manga> = emptyList(),
    )

    private enum class Mode { AUTO, UPLOAD }

    private companion object {
        val UPLOAD_DEBOUNCE = 5.seconds
        const val FOREGROUND_MIN_INTERVAL_MS = 60_000L
        const val NETWORK_MIN_INTERVAL_MS = 10_000L
    }
}
