package eu.kanade.tachiyomi.data.anime

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Vídeos de um episódio, como no EpisodeLoader do Aniyomi: servidores (ext-lib 16+) ou a lista antiga de vídeos. */
object AnimeVideos {

    suspend fun load(source: AnimeSource, episode: SEpisode): List<Video> {
        val http = source as? AnimeHttpSource
        val hosters = if (http != null && hasHosters(http)) {
            http.getHosterList(episode).let { http.run { it.sortHosters() } }
        } else {
            source.getVideoList(episode).let { list -> http?.run { list.sortVideos() } ?: list }.toHosterList()
        }
        return hosters.flatMap { hoster ->
            try {
                val videos = hoster.videoList ?: source.getVideoList(hoster)
                videos.map { video ->
                    if (video.videoUrl != "null" || http == null) video else video.copy(videoUrl = http.getVideoUrl(video))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Anime: servidor ${hoster.hosterName} falhou" }
                emptyList()
            }
        }
            .mapNotNull { video ->
                if (http == null || video.initialized) video else http.resolveVideo(video)
            }
            .filter { it.videoUrl.isNotBlank() && it.videoUrl != "null" }
            .let { list -> http?.run { list.sortVideos() } ?: list }
    }

    /** As extensões antigas não têm getHosterList: aí usa-se getVideoList(episódio). */
    private fun hasHosters(source: AnimeHttpSource): Boolean {
        var current: Class<*> = source.javaClass
        while (true) {
            if (current == ParsedAnimeHttpSource::class.java || current == AnimeHttpSource::class.java) return false
            if (current.declaredMethods.any { it.name in listOf("getHosterList", "hosterListRequest", "hosterListParse") }) {
                return true
            }
            current = current.superclass ?: return false
        }
    }

    /** O vídeo como item do Media3, com o tipo pelo URL e as legendas externas da fonte. */
    fun mediaItem(id: String, video: Video): MediaItem {
        val url = video.videoUrl
        val mime = when {
            ".m3u8" in url -> MimeTypes.APPLICATION_M3U8
            ".mpd" in url -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val subtitles = video.subtitleTracks.map { track ->
            val type = when (track.url.substringBefore('?').substringAfterLast('.').lowercase()) {
                "vtt" -> MimeTypes.TEXT_VTT
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(type)
                .setLanguage(track.lang)
                .setLabel(track.lang)
                .build()
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            .setMimeType(mime)
            .setSubtitleConfigurations(subtitles)
            .build()
    }

    /** Cabeçalhos para o player e os downloads: os do vídeo, senão os da fonte. */
    fun headers(source: AnimeSource, video: Video): Map<String, String> {
        val headers = video.headers ?: (source as? AnimeHttpSource)?.headers ?: return emptyMap()
        return headers.names().associateWith { headers[it].orEmpty() }
    }
}
