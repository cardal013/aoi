package eu.kanade.tachiyomi.data.anime

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.account.supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Que anime de que fonte corresponde a cada anime do MAL: guardado no aparelho e na tabela
 * public.anime_sources (migration 20261028_anime_sources.sql), para valer noutros telemóveis.
 * Também guarda onde se parou em cada episódio (só no aparelho).
 */
@Inject
@SingleIn(AppScope::class)
class AnimeLinks(
    context: Context,
    private val json: Json,
) {

    @Serializable
    data class Link(
        @SerialName("source_id") val sourceId: String,
        @SerialName("anime_url") val animeUrl: String,
        val title: String? = null,
    )

    private val prefs = context.getSharedPreferences("aoi_anime_links", Context.MODE_PRIVATE)
    private val positions = context.getSharedPreferences("aoi_anime_positions", Context.MODE_PRIVATE)

    private fun userId(): String = supabase.auth.currentUserOrNull()?.id ?: "local"
    private fun key(malId: Long) = "${userId()}:$malId"

    /** A ligação deste aparelho; se não houver, a da cloud (e fica guardada). */
    suspend fun get(malId: Long): Link? {
        prefs.getString(key(malId), null)?.let { return json.decodeFromString<Link>(it) }
        val remote = withIOContext {
            try {
                val uid = supabase.auth.currentUserOrNull()?.id ?: return@withIOContext null
                supabase.postgrest["anime_sources"]
                    .select(Columns.raw("source_id, anime_url, title")) {
                        filter {
                            eq("user_id", uid)
                            eq("mal_id", malId)
                        }
                    }
                    .decodeList<Link>()
                    .firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anime: não deu para ler a ligação da cloud" }
                null
            }
        }
        remote?.let { prefs.edit { putString(key(malId), json.encodeToString(it)) } }
        return remote
    }

    suspend fun set(malId: Long, link: Link) {
        prefs.edit { putString(key(malId), json.encodeToString(link)) }
        withIOContext {
            try {
                val uid = supabase.auth.currentUserOrNull()?.id ?: return@withIOContext
                supabase.postgrest["anime_sources"].upsert(
                    LinkRow(uid, malId, link.sourceId, link.animeUrl, link.title),
                ) { onConflict = "user_id,mal_id" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anime: não deu para guardar a ligação na cloud" }
            }
        }
    }

    suspend fun clear(malId: Long) {
        prefs.edit { remove(key(malId)) }
        withIOContext {
            try {
                val uid = supabase.auth.currentUserOrNull()?.id ?: return@withIOContext
                supabase.postgrest["anime_sources"].delete {
                    filter {
                        eq("user_id", uid)
                        eq("mal_id", malId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anime: não deu para apagar a ligação na cloud" }
            }
        }
    }

    fun position(episodeUrl: String): Long = positions.getLong(episodeUrl, 0)

    fun savePosition(episodeUrl: String, ms: Long) {
        positions.edit { putLong(episodeUrl, ms) }
    }

    @Serializable
    private data class LinkRow(
        @SerialName("user_id") val userId: String,
        @SerialName("mal_id") val malId: Long,
        @SerialName("source_id") val sourceId: String,
        @SerialName("anime_url") val animeUrl: String,
        val title: String?,
    )
}
