package eu.kanade.tachiyomi.data.anime

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Episódios transferidos com o Media3 para a pasta do Aoi (Android/data/…/files/anime): HLS, DASH ou ficheiro.
 * O id de cada download é o URL do episódio na fonte. O player lê da mesma cache, por isso vê-se sem rede.
 */
@UnstableApi
@Inject
@SingleIn(AppScope::class)
class AnimeDownloads(
    private val context: Context,
    private val network: NetworkHelper,
    private val json: Json,
) {

    @Serializable
    data class Info(val malId: Long, val animeTitle: String, val episodeName: String, val episodeNumber: Float)

    data class Item(val id: String, val info: Info?, val state: Int, val percent: Float)

    private val headersPrefs = context.getSharedPreferences("aoi_anime_headers", Context.MODE_PRIVATE)

    private val database by lazy { StandaloneDatabaseProvider(context) }

    val cache: SimpleCache by lazy {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "anime")
        SimpleCache(dir, NoOpCacheEvictor(), database)
    }

    /** Pedidos à rede com os cabeçalhos guardados para o servidor de cada URL (os segmentos HLS também precisam). */
    val upstream: DataSource.Factory by lazy {
        val http = OkHttpDataSource.Factory(network.client)
        ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
            val headers = spec.uri.host?.let { headersFor(it) }.orEmpty()
            if (headers.isEmpty()) spec else spec.withAdditionalHeaders(headers)
        }
    }

    /** Para o player: o que está na cache vem de lá, o resto da rede. */
    val playerDataSource: DataSource.Factory by lazy {
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setCacheWriteDataSinkFactory(null)
    }

    val manager: DownloadManager by lazy {
        DownloadManager(context, database, cache, upstream, Executors.newFixedThreadPool(4))
            .apply {
                maxParallelDownloads = 2
                addListener(
                    object : DownloadManager.Listener {
                        override fun onDownloadChanged(manager: DownloadManager, download: Download, e: Exception?) {
                            refresh()
                        }

                        override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                            refresh()
                        }
                    },
                )
            }
    }

    private val _items = MutableStateFlow<Map<String, Item>>(emptyMap())
    val items: StateFlow<Map<String, Item>> = _items.asStateFlow()

    fun refresh() {
        val list = mutableMapOf<String, Item>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                list[d.request.id] = Item(d.request.id, info(d), d.state, d.percentDownloaded)
            }
        }
        manager.currentDownloads.forEach { d -> list[d.request.id] = Item(d.request.id, info(d), d.state, d.percentDownloaded) }
        _items.value = list
    }

    private fun info(d: Download): Info? = runCatching { json.decodeFromString<Info>(String(d.request.data)) }.getOrNull()

    fun saveHeaders(url: String, headers: Map<String, String>) {
        val host = Uri.parse(url).host ?: return
        if (headers.isNotEmpty()) headersPrefs.edit { putString(host, json.encodeToString(headers)) }
    }

    private fun headersFor(host: String): Map<String, String>? =
        headersPrefs.getString(host, null)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }

    fun downloaded(id: String): Download? =
        manager.downloadIndex.getDownload(id)?.takeIf { it.state == Download.STATE_COMPLETED }

    /** Prepara o pedido (escolhe uma só qualidade nos HLS/DASH) e põe-no na fila. */
    suspend fun start(id: String, mediaItem: MediaItem, info: Info) {
        val helper = DownloadHelper.forMediaItem(context, mediaItem, DefaultRenderersFactory(context), upstream)
        try {
            suspendCancellableCoroutine { cont ->
                helper.prepare(
                    object : DownloadHelper.Callback {
                        override fun onPrepared(helper: DownloadHelper) = cont.resume(Unit)
                        override fun onPrepareError(helper: DownloadHelper, e: java.io.IOException) = cont.resumeWithException(e)
                    },
                )
            }
            val request = helper.getDownloadRequest(id, json.encodeToString(info).toByteArray())
            DownloadService.sendAddDownload(context, AnimeDownloadService::class.java, request, true)
        } finally {
            helper.release()
        }
    }

    fun remove(id: String) {
        DownloadService.sendRemoveDownload(context, AnimeDownloadService::class.java, id, false)
    }
}

/** Serviço em primeiro plano que faz os downloads, com a notificação de progresso. */
@UnstableApi
class AnimeDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.anime_downloads_channel,
    0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = appGraph.animeDownloads.manager.also { appGraph.animeDownloads.refresh() }

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, R.drawable.ic_mihon, null, null, downloads, notMetRequirements)

    private companion object {
        const val NOTIFICATION_ID = 7301
        const val CHANNEL_ID = "aoi_anime_downloads"
    }
}
