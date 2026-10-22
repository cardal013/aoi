package eu.kanade.tachiyomi.data.anime

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeStatus
import kotlin.time.Duration.Companion.minutes

/** Pesquisa de animes na API pública do AniList (sem login). */
@Inject
@SingleIn(AppScope::class)
class AniListAnimeApi(
    network: NetworkHelper,
    private val json: Json,
) {

    // O AniList aceita 90 pedidos por minuto
    private val client = network.client.newBuilder()
        .rateLimit(permits = 80, period = 1.minutes)
        .build()

    suspend fun search(query: String): List<Anime> = withIOContext {
        val payload = buildJsonObject {
            put("query", SEARCH_QUERY)
            putJsonObject("variables") { put("search", query) }
        }
        with(json) {
            client.newCall(POST(API_URL, body = payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
                .parseAs<SearchResponse>()
                .data.page.media
                .map { it.toAnime() }
        }
    }

    private fun Media.toAnime() = Anime(
        anilistId = id,
        title = title.romaji ?: title.english ?: "#$id",
        titleEnglish = title.english,
        coverUrl = coverImage?.large,
        format = format,
        episodes = episodes,
        seasonYear = seasonYear,
        status = AnimeStatus.PLAN_TO_WATCH,
    )

    @Serializable
    private data class SearchResponse(val data: Data)

    @Serializable
    private data class Data(@SerialName("Page") val page: Page)

    @Serializable
    private data class Page(val media: List<Media>)

    @Serializable
    private data class Media(
        val id: Long,
        val title: Title,
        val coverImage: CoverImage? = null,
        val format: String? = null,
        val episodes: Long? = null,
        val seasonYear: Long? = null,
    )

    @Serializable
    private data class Title(val romaji: String? = null, val english: String? = null)

    @Serializable
    private data class CoverImage(val large: String? = null)

    private companion object {
        const val API_URL = "https://graphql.anilist.co/"

        val SEARCH_QUERY = """
            query (${'$'}search: String) {
              Page(perPage: 30) {
                media(search: ${'$'}search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) {
                  id
                  title { romaji english }
                  coverImage { large }
                  format
                  episodes
                  seasonYear
                }
              }
            }
        """.trimIndent()
    }
}
