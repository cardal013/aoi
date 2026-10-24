package eu.kanade.tachiyomi.data.anime

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.account.supabase
import eu.kanade.tachiyomi.util.system.toast
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.repository.AnimeFolderRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import java.time.Instant

/**
 * Sync da lista de animes do MyAnimeList (o MAL manda; o Supabase guarda uma cópia).
 * - Cada alteração fica primeiro no aparelho (dirty_at = fila offline) e vai logo para a edge function `mal`,
 *   que a grava na cópia e no MAL. Sem rede (ou Supabase em baixo) fica na fila e vai no próximo sync.
 * - Sync completo (ao abrir a app, quando volta a rede — pelo CloudSync —, ao entrar na aba Anime e ao puxar
 *   para baixo): manda a fila, confirma a ligação ao MAL, pede ao servidor para ler a lista do MAL e lê a cópia.
 *   Numa instalação nova é assim que a lista chega (do MAL, através da cópia).
 * Só animes: os mangas do MAL nunca entram no Aoi.
 */
@Inject
@SingleIn(AppScope::class)
class MalAnimeSync(
    private val context: Context,
    private val api: MalAnimeApi,
    private val animeRepository: AnimeRepository,
    private val folderRepository: AnimeFolderRepository,
    private val libraryPreferences: LibraryPreferences,
) {

    /** Estado da ligação ao MAL para a aba Anime. */
    enum class Connection {
        // sem conta do Aoi com sessão: não dá para ligar o MAL
        NO_ACCOUNT,

        // ainda não se perguntou ao servidor neste aparelho
        UNKNOWN,
        CONNECTED,
        DISCONNECTED,
    }

    private val mutex = Mutex()
    private val mutableIsSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = mutableIsSyncing.asStateFlow()

    private var scope: CoroutineScope? = null
    private var lastFullSyncAt = 0L

    /** Arranca com o CloudSync (no processo principal e só com SUPABASE_URL). */
    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Conta do Aoi com sessão (null sem sessão ou sem Supabase configurado). */
    val currentUserId: Flow<String?> = if (BuildConfig.SUPABASE_URL.isBlank()) {
        flowOf(null)
    } else {
        supabase.auth.sessionStatus
            .map { (it as? SessionStatus.Authenticated)?.session?.user?.id }
            .distinctUntilChanged()
    }

    private fun currentUserIdOrNull(): String? {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            return null
        }
        return supabase.auth.currentUserOrNull()?.id
    }

    /** Estado da ligação ao MAL da conta [userId], a partir do que ficou guardado no último contacto. */
    fun connectionFlow(userId: String?): Flow<Pair<Connection, String>> {
        if (userId == null) {
            return flowOf(Connection.NO_ACCOUNT to "")
        }
        return combine(
            libraryPreferences.malConnectionChecked(userId).changes(),
            libraryPreferences.malUsername(userId).changes(),
        ) { checked, malUsername ->
            val connection = when {
                !checked -> Connection.UNKNOWN
                malUsername.isNotEmpty() -> Connection.CONNECTED
                else -> Connection.DISCONNECTED
            }
            connection to malUsername
        }
    }

    /**
     * Pede um sync completo. Sem [force], não repete se houve um há menos de 10 s
     * (entrar e sair da aba Anime várias vezes seguidas).
     */
    fun requestSync(force: Boolean = false) {
        val scope = scope ?: return
        val userId = currentUserIdOrNull() ?: return
        val now = System.currentTimeMillis()
        val syncedRecently = now - lastFullSyncAt < MIN_SYNC_INTERVAL_MS
        if (syncedRecently && !force) {
            return
        }
        lastFullSyncAt = now

        scope.launchIO {
            mutex.withLock {
                mutableIsSyncing.value = true
                try {
                    runFullSync(userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "MAL: sync falhou" }
                } finally {
                    mutableIsSyncing.value = false
                }
            }
        }
    }

    /** Manda já a fila (chamado depois de cada alteração local a um anime, pasta ou ordem). */
    fun pushChanges() {
        val scope = scope ?: return
        val userId = currentUserIdOrNull() ?: return
        scope.launchIO {
            mutex.withLock {
                try {
                    val queueSent = sendQueue(userId)
                    if (queueSent) {
                        sendLayout(userId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "MAL: envio da fila falhou" }
                }
            }
        }
    }

    /** URL para o botão "Connect MyAnimeList". Lança exceção se não houver rede ou sessão. */
    suspend fun getLoginUrl(): String = api.getLoginUrl()

    /** Desliga o MAL da conta. Devolve false se não deu (sem rede). */
    suspend fun disconnect(): Boolean {
        val userId = currentUserIdOrNull() ?: return false
        return try {
            api.disconnect()
            saveConnection(userId, MalAnimeApi.Connection(connected = false, malUsername = null))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "MAL: desligar falhou" }
            false
        }
    }

    /**
     * Regresso do login no MAL (aoi://mal-anime-auth?mal=...): avisa o utilizador e, se ligou,
     * faz logo um sync completo (que também confirma a ligação e traz a lista).
     */
    fun onLoginResult(result: String?) {
        val connected = result == "connected"
        val message = if (connected) MR.strings.anime_mal_connected else MR.strings.anime_mal_connect_failed
        scope?.launchIO {
            withUIContext { context.toast(message) }
        }
        if (connected) {
            requestSync(force = true)
        }
    }

    // ---------- Sync completo ----------

    /**
     * 1. manda a fila (se não der, o Supabase está inacessível e não vale a pena continuar)
     * 2. confirma no servidor se o MAL está ligado (sem MAL ligado não há lista)
     * 3. pede ao servidor o sync com o MAL (se o MAL estiver em baixo, continua com a cópia)
     * 4. lê a cópia
     */
    private suspend fun runFullSync(userId: String) {
        val queueSent = sendQueue(userId)
        if (!queueSent) {
            return
        }
        sendLayout(userId)

        val connection = api.getConnection()
        saveConnection(userId, connection)
        if (!connection.connected) {
            return
        }

        val stillConnected = syncWithMal(userId)
        if (!stillConnected) {
            return
        }
        importCopy(userId)
        importFoldersAndTabs(userId)
    }

    /** Guarda o estado da ligação (o que a aba Anime mostra, mesmo sem rede). */
    private fun saveConnection(userId: String, connection: MalAnimeApi.Connection) {
        libraryPreferences.malConnectionChecked(userId).set(true)
        val malUsername = if (connection.connected) connection.malUsername.orEmpty().ifEmpty { "?" } else ""
        libraryPreferences.malUsername(userId).set(malUsername)
    }

    /**
     * Pede ao servidor para sincronizar a cópia com o MAL. MAL em baixo não é erro: fica a cópia.
     * Devolve false se o servidor disser que o MAL já não está ligado (token revogado).
     */
    private suspend fun syncWithMal(userId: String): Boolean {
        try {
            api.syncList()
        } catch (e: MalAnimeApi.FunctionException) {
            if (e.errorCode == ERROR_MAL_NOT_CONNECTED) {
                saveConnection(userId, MalAnimeApi.Connection(connected = false, malUsername = null))
                return false
            }
            if (e.errorCode == ERROR_MAL_UNAVAILABLE) {
                logcat(LogPriority.WARN) { "MAL: em baixo, usa-se a cópia do Supabase" }
                return true
            }
            throw e
        }
        return true
    }

    /**
     * Lê da cópia o que mudou desde o último import (menos 10 min, por causa dos relógios) e aplica-o,
     * sem mexer no que ainda está na fila deste aparelho. O marco só avança se tudo correu bem.
     */
    private suspend fun importCopy(userId: String) {
        val start = System.currentTimeMillis()
        val marker = libraryPreferences.malAnimeDownloadSync(userId)
        val lastImport = marker.get()
        val updatedSince = if (lastImport > 0L) {
            Instant.ofEpochMilli(lastImport - DOWNLOAD_OVERLAP_MS).toString()
        } else {
            null
        }

        val remoteAnimes = api.fetchCopy(userId, updatedSince)
        // O que está na fila (MAL) ou com pasta/ordem por enviar fica como está aqui
        val queuedIds = animeRepository.getDirty(userId).map { it.malId }
        val layoutPendingIds = animeRepository.getLayoutDirty(userId).map { it.malId }
        val pendingIds = (queuedIds + layoutPendingIds).toSet()
        val animesToApply = remoteAnimes.filter { it.malId !in pendingIds }
        animeRepository.upsertFromCloud(userId, animesToApply)
        marker.set(start)
        logcat(LogPriority.INFO) { "MAL: cópia lida=${remoteAnimes.size} | aplicados=${animesToApply.size}" }
    }

    // ---------- Fila ----------

    /**
     * Manda à edge function cada alteração local por enviar (acrescentar/mudar ou remover).
     * O servidor grava-a na cópia e tenta o MAL; se o MAL falhar, fica pendente lá e vai no sync seguinte,
     * por isso aqui sai da fila assim que o servidor a guardou.
     * Um erro definitivo (4xx) também a tira da fila (fica no log; o próximo import repõe o que está na cópia).
     * Devolve false se não chegou ao servidor (sem rede ou Supabase em baixo): o resto fica para depois.
     */
    private suspend fun sendQueue(userId: String): Boolean {
        val pendingAnimes = animeRepository.getDirty(userId)
        for (anime in pendingAnimes) {
            try {
                val result = if (anime.deleted) api.sendRemove(anime.malId) else api.sendUpdate(anime)
                if (!result.malSynced) {
                    logcat(LogPriority.INFO) { "MAL: anime ${anime.malId} na cópia, MAL depois (${result.malError})" }
                }
            } catch (e: MalAnimeApi.FunctionException) {
                val isDefinitiveError = e.httpCode in HTTP_CLIENT_ERRORS && e.httpCode != HTTP_UNAUTHORIZED
                if (!isDefinitiveError) {
                    logcat(LogPriority.WARN, e) { "MAL: servidor não guardou o anime ${anime.malId}" }
                    return false
                }
                logcat(LogPriority.ERROR, e) { "MAL: servidor recusou o anime ${anime.malId}, sai da fila" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "MAL: sem ligação ao servidor, a fila fica para depois" }
                return false
            }

            val dirtyAt = anime.dirtyAt
            if (dirtyAt != null) {
                animeRepository.clearDirty(userId, anime.malId, dirtyAt)
            }
        }
        return true
    }

    // ---------- Pastas, ordem à mão e modo das abas (direto ao Supabase) ----------

    /**
     * Envia ao Supabase as pastas, as mudanças de pasta/ordem dos animes e os modos das abas por enviar.
     * Ordem: pastas novas/mudadas primeiro (os animes apontam para elas), depois os animes, depois as pastas
     * apagadas (a chave estrangeira solta os animes) e por fim os modos.
     * Para no primeiro erro (sem rede): o resto fica marcado para o próximo envio.
     */
    private suspend fun sendLayout(userId: String) {
        try {
            val dirtyFolders = folderRepository.getDirty(userId)
            val (deletedFolders, changedFolders) = dirtyFolders.partition { it.deleted }

            api.upsertFolders(userId, changedFolders)
            for (folder in changedFolders) {
                folder.dirtyAt?.let { folderRepository.clearDirty(userId, folder.id, it) }
            }

            for (anime in animeRepository.getLayoutDirty(userId)) {
                api.updateAnimeLayout(userId, anime)
                anime.layoutDirtyAt?.let { animeRepository.clearLayoutDirty(userId, anime.malId, it) }
            }

            for (folder in deletedFolders) {
                api.deleteFolder(userId, folder.id)
                folderRepository.deleteRow(userId, folder.id)
            }

            for ((tab, modeAndDirtyAt) in folderRepository.getDirtyTabModes(userId)) {
                val (mode, dirtyAt) = modeAndDirtyAt
                api.upsertTabMode(userId, tab, mode)
                folderRepository.clearTabModeDirty(userId, tab, dirtyAt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "MAL: pastas/ordem ficam para o próximo envio" }
        }
    }

    /**
     * Lê do Supabase as pastas (todas; são poucas) e os modos das abas e aplica-os, sem mexer no que
     * está por enviar daqui. Uma pasta que já não está no Supabase (apagada noutro aparelho) sai daqui.
     */
    private suspend fun importFoldersAndTabs(userId: String) {
        val remoteFolders = api.fetchFolders(userId)
        val localFolders = folderRepository.getAllIncludingDeleted(userId)
        val pendingFolderIds = localFolders.filter { it.dirtyAt != null }.map { it.id }.toSet()

        val foldersToApply = remoteFolders.filter { it.id !in pendingFolderIds }
        folderRepository.upsertFromCloud(userId, foldersToApply)

        val remoteFolderIds = remoteFolders.map { it.id }.toSet()
        for (folder in localFolders) {
            val deletedElsewhere = folder.dirtyAt == null && folder.id !in remoteFolderIds
            if (deletedElsewhere) {
                folderRepository.deleteRow(userId, folder.id)
            }
        }

        folderRepository.upsertTabModesFromCloud(userId, api.fetchTabModes(userId))
        logcat(LogPriority.INFO) { "MAL: pastas lidas=${remoteFolders.size} | aplicadas=${foldersToApply.size}" }
    }

    private companion object {
        const val MIN_SYNC_INTERVAL_MS = 10_000L
        const val DOWNLOAD_OVERLAP_MS = 10 * 60 * 1000L
        const val ERROR_MAL_NOT_CONNECTED = "mal_not_connected"
        const val ERROR_MAL_UNAVAILABLE = "mal_unavailable"
        const val HTTP_UNAUTHORIZED = 401
        val HTTP_CLIENT_ERRORS = 400..499
    }
}
