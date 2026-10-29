package eu.kanade.tachiyomi.data.account

import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.anime.MalAnimeApi
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.repository.TrackRepository
import java.util.UUID

/**
 * Mangas do Aoi com o MyAnimeList. A comparação e o sync são da edge function `mal` (manga_list.ts);
 * aqui fica o que só o telemóvel sabe ou pode fazer:
 * - as fontes instaladas (user_devices), para o site só deixar escolher as que existem
 * - as ligações ao MAL que já estão no tracker do Mihon (user_manga_mal), para não se procurarem pelo título
 * - um backup automático antes de o MAL mexer pela primeira vez na biblioteca deste telemóvel
 * - pedir o sync ao servidor depois de cada sync com a cloud
 */
@Inject
@SingleIn(AppScope::class)
class MangaMalSync(
    private val sourceManager: SourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val mangaRepository: MangaRepository,
    private val trackRepository: TrackRepository,
    private val backupCreatorFactory: BackupCreator.Factory,
    private val storageManager: StorageManager,
    private val malApi: MalAnimeApi,
    private val json: Json,
) {

    /** Envia a lista das fontes instaladas, só se mudou desde a última vez. */
    suspend fun uploadDevice(userId: String) = withIOContext {
        val sources = sourceManager.getOnlineSources()
            .map { DeviceSource(id = it.id.toString(), name = it.name, lang = it.lang, baseUrl = it.baseUrl) }
            .sortedBy { it.id }
        // As extensões ainda podem estar a carregar: uma lista vazia não substitui a que lá está
        if (sources.isEmpty()) return@withIOContext
        val encoded = json.encodeToString(sources)
        val sent = libraryPreferences.cloudDeviceSourcesSent(userId)
        if (sent.get() == encoded) return@withIOContext

        val deviceId = libraryPreferences.cloudDeviceId.get().ifEmpty {
            UUID.randomUUID().toString().also { libraryPreferences.cloudDeviceId.set(it) }
        }
        supabase.postgrest["user_devices"].upsert(
            UserDeviceRemote(userId = userId, deviceId = deviceId, deviceName = Build.MODEL, sources = sources),
        ) { onConflict = "user_id,device_id" }
        sent.set(encoded)
    }

    /**
     * Envia as ligações ao MAL do tracker do Mihon, como pistas (PENDING com o mal_id: a função liga-as sem
     * procurar). As que já existem na cloud ficam como estão (o tracker não se mexe nem manda).
     */
    suspend fun uploadTrackerLinks(userId: String) = withIOContext {
        val malTracks = trackRepository.getTracksAsFlow().first().filter { it.trackerId == MAL_TRACKER_ID && it.remoteId > 0 }
        if (malTracks.isEmpty()) return@withIOContext
        val favorites = mangaRepository.getFavorites().associateBy { it.id }
        val hints = malTracks
            .mapNotNull { track ->
                favorites[track.mangaId]?.let { LinkHintRemote(userId, mangaRemoteId(it.source, it.url), track.remoteId) }
            }
            .distinctBy { it.mangaId }
        if (hints.isEmpty()) return@withIOContext
        try {
            supabase.postgrest["user_manga_mal"].upsert(hints) {
                onConflict = "user_id,manga_id"
                ignoreDuplicates = true
            }
            return@withIOContext
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "MAL: ligações do tracker em lote falharam, vão uma a uma" }
        }
        // Um manga que ainda não chegou à cloud parte o lote inteiro (chave estrangeira): vão um a um
        hints.forEach { hint ->
            try {
                supabase.postgrest["user_manga_mal"].upsert(hint) {
                    onConflict = "user_id,manga_id"
                    ignoreDuplicates = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "MAL: ligação do tracker não enviada (${hint.mangaId})" }
            }
        }
    }

    /**
     * Com o sync dos mangas ligado (ON), faz um backup automático (como o do Mihon) antes do primeiro import
     * que pode trazer alterações do MAL. Uma vez por conta. Sem pasta de backups não faz e tenta da próxima.
     */
    suspend fun backupBeforeFirstSync(userId: String) = withIOContext {
        val done = libraryPreferences.malMangaBackupDone(userId)
        if (done.get()) return@withIOContext
        val state = supabase.postgrest["user_mal_manga_sync"]
            .select(columns = Columns.list("state")) { filter { eq("user_id", userId) } }
            .decodeSingleOrNull<SyncStateRow>()
            ?.state
        if (state != ON) return@withIOContext

        val directory = storageManager.getAutomaticBackupsDirectory()
        if (directory == null) {
            logcat(LogPriority.WARN) { "MAL: sem pasta de backups, o backup antes do sync dos mangas fica para depois" }
            return@withIOContext
        }
        val location = backupCreatorFactory.create(isAutoBackup = true).backup(directory.uri, BackupOptions())
        logcat(LogPriority.INFO) { "MAL: backup antes do sync dos mangas em $location" }
        done.set(true)
    }

    /**
     * Pede o sync dos mangas ao servidor; continua enquanto ficar trabalho (no máximo algumas vezes).
     * Devolve false se o sync dos mangas está desligado (OFF).
     */
    suspend fun syncWithMal(): Boolean = withIOContext {
        repeat(MAX_SYNC_CALLS) {
            val (state, more) = malApi.syncManga()
            if (state == OFF) return@withIOContext false
            if (!more) return@withIOContext true
        }
        true
    }

    @Serializable
    private data class DeviceSource(
        val id: String,
        val name: String,
        val lang: String,
        @SerialName("base_url") val baseUrl: String,
    )

    @Serializable
    private data class UserDeviceRemote(
        @SerialName("user_id") val userId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("device_name") val deviceName: String,
        val sources: List<DeviceSource>,
    )

    @Serializable
    private data class LinkHintRemote(
        @SerialName("user_id") val userId: String,
        @SerialName("manga_id") val mangaId: String,
        @SerialName("mal_id") val malId: Long,
    )

    @Serializable
    private data class SyncStateRow(val state: String)

    private companion object {
        // TrackerManager.myAnimeList
        const val MAL_TRACKER_ID = 1L
        const val ON = "ON"
        const val OFF = "OFF"
        const val MAX_SYNC_CALLS = 3
    }
}
