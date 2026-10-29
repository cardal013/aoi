package eu.kanade.tachiyomi.data.anime

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.account.supabase
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.jsonMime
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeFolder
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.model.AnimeTabMode
import tachiyomi.domain.anime.model.FolderSortOrder
import java.io.IOException
import java.time.OffsetDateTime

/**
 * Fala com o MyAnimeList (só a lista de ANIMES, nunca os mangas) através da edge function `mal`
 * do Supabase, e lê a cópia da lista (tabela user_mal_anime) pelo PostgREST. As pastas, a ordem à mão e o
 * modo das abas (só do Aoi) vão direto ao Supabase (user_anime_folders, user_mal_anime, user_anime_tabs).
 * A pesquisa vai direta ao MAL quando há MAL_CLIENT_ID no ignorance/local.properties; senão vai pela função.
 * O token do MAL e o client_secret nunca chegam à app: ficam no servidor.
 */
@Inject
@SingleIn(AppScope::class)
class MalAnimeApi(
    network: NetworkHelper,
    private val json: Json,
) {

    private val client = network.client

    /** Erro devolvido pela edge function: [httpCode] e o [errorCode] do corpo (`{ error }`). */
    class FunctionException(val httpCode: Int, val errorCode: String) :
        Exception("Edge function mal: $httpCode $errorCode")

    /** Estado da ligação ao MAL da conta com sessão; [malUserId] diz qual é a conta do MAL (null se desligado). */
    data class Connection(val connected: Boolean, val malUsername: String?, val malUserId: Long?)

    /** Resposta de update/remove: se o servidor guardou e se já chegou ao MAL. */
    data class SendResult(val saved: Boolean, val malSynced: Boolean, val malError: String?)

    // ---------- Edge function ----------

    /**
     * Chama a ação [action] da edge function `mal` com o JWT da sessão do Aoi.
     * Devolve o corpo JSON. Lança [FunctionException] se a função responder com erro
     * e [IOException] se não houver rede ou sessão.
     */
    private suspend fun callFunction(action: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val accessToken = currentAccessToken() ?: throw IOException("Sem sessão do Aoi")
        val body = buildJsonObject {
            put("action", action)
            fields()
        }
        val headers = Headers.Builder()
            .add("Authorization", "Bearer $accessToken")
            .add("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()
        val request = POST(functionUrl(), headers = headers, body = body.toString().toRequestBody(jsonMime))

        val responseText: String
        val responseCode: Int
        client.newCall(request).await().use { response ->
            responseText = response.body.string()
            responseCode = response.code
        }

        val responseJson = runCatching { json.parseToJsonElement(responseText) as JsonObject }.getOrNull()
        val isSuccess = responseCode in 200..299
        if (!isSuccess) {
            val errorCode = responseJson?.get("error")?.jsonPrimitive?.content ?: "http_$responseCode"
            throw FunctionException(responseCode, errorCode)
        }
        return responseJson ?: throw FunctionException(responseCode, "invalid_response")
    }

    private fun functionUrl(): String = "${BuildConfig.SUPABASE_URL}/functions/v1/mal"

    /** JWT da sessão do Aoi, renovado se faltar menos de 2 minutos para expirar. null se não houver sessão. */
    private suspend fun currentAccessToken(): String? {
        val session = supabase.auth.currentSessionOrNull() ?: return null
        val millisecondsLeft = session.expiresAt.toEpochMilliseconds() - System.currentTimeMillis()
        if (millisecondsLeft >= SESSION_MARGIN_MS) {
            return session.accessToken
        }
        supabase.auth.refreshCurrentSession()
        return supabase.auth.currentSessionOrNull()?.accessToken
    }

    /** Pergunta ao servidor se a conta tem o MAL ligado e com que nome. */
    suspend fun getConnection(): Connection = withIOContext {
        val response = json.decodeFromJsonElement(StatusResponse.serializer(), callFunction("status"))
        Connection(connected = response.connected, malUsername = response.malUsername, malUserId = response.malUserId)
    }

    /** URL de autorização do MAL para abrir no browser; no fim o MAL volta a aoi://mal-anime-auth. */
    suspend fun getLoginUrl(): String = withIOContext {
        val response = callFunction("login") { put("return_to", "app") }
        response.getValue("url").jsonPrimitive.content
    }

    /** Desliga o MAL da conta (o token sai do servidor; a cópia da lista fica). */
    suspend fun disconnect() {
        withIOContext { callFunction("disconnect") }
    }

    /**
     * Pede ao servidor o sync da lista: reenvia o que lá ficou pendente, lê a lista do MAL,
     * passa On Hold / Dropped a Plan to Watch no MAL e atualiza a cópia user_mal_anime.
     */
    suspend fun syncList() {
        withIOContext {
            val summary = callFunction("sync")
            logcat(LogPriority.INFO) { "MAL: sync no servidor | $summary" }
        }
    }

    /**
     * Pede ao servidor o sync dos mangas com o MAL (só estado e capítulos lidos; ver manga_list.ts da função).
     * Devolve o estado do sync (OFF, PREVIEW, ON) e se ficou trabalho para o pedido seguinte.
     */
    suspend fun syncManga(): Pair<String, Boolean> = withIOContext {
        val summary = callFunction("manga_sync")
        logcat(LogPriority.INFO) { "MAL: mangas no servidor | $summary" }
        val state = summary["state"]?.jsonPrimitive?.content ?: "OFF"
        state to (summary["more"]?.jsonPrimitive?.booleanOrNull == true)
    }

    /** Envia um anime acrescentado ou mudado (estado e episódios, mais a ficha para a cópia). */
    suspend fun sendUpdate(anime: Anime): SendResult = withIOContext {
        val response = callFunction("update") {
            put("mal_id", anime.malId)
            put("status", anime.status.name)
            put("episodes_watched", anime.episodesWatched)
            put("title", anime.title)
            put("title_english", anime.titleEnglish)
            put("cover_url", anime.coverUrl)
            put("media_type", anime.mediaType)
            put("num_episodes", anime.numEpisodes)
            put("start_date", anime.startDate)
        }
        decodeSendResult(response)
    }

    /** Envia a remoção de um anime. */
    suspend fun sendRemove(malId: Long): SendResult = withIOContext {
        val response = callFunction("remove") { put("mal_id", malId) }
        decodeSendResult(response)
    }

    private fun decodeSendResult(response: JsonObject): SendResult {
        val decoded = json.decodeFromJsonElement(SendResponse.serializer(), response)
        return SendResult(saved = decoded.saved, malSynced = decoded.malSynced, malError = decoded.malError)
    }

    // ---------- Pesquisa ----------

    /**
     * Pesquisa animes no MAL (sem +18). Direto ao MAL com o client_id se o houver; senão pela função.
     * Se o MAL recusar a pesquisa (por exemplo, curta demais) devolve uma lista vazia.
     */
    suspend fun search(query: String): List<Anime> = withIOContext {
        val hasClientId = BuildConfig.MAL_CLIENT_ID.isNotBlank()
        if (hasClientId) {
            searchOnMal(query)
        } else {
            searchThroughFunction(query)
        }
    }

    private suspend fun searchOnMal(query: String): List<Anime> {
        val url = MAL_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", SEARCH_RESULT_LIMIT.toString())
            .addQueryParameter("nsfw", "false")
            .addQueryParameter("fields", SEARCH_FIELDS)
            .build()
        val headers = Headers.Builder().add("X-MAL-CLIENT-ID", BuildConfig.MAL_CLIENT_ID).build()

        client.newCall(GET(url.toString(), headers)).await().use { response ->
            if (response.code == HTTP_BAD_REQUEST) {
                logcat(LogPriority.WARN) { "MAL: pesquisa recusada (400)" }
                return emptyList()
            }
            if (!response.isSuccessful) {
                throw IOException("MAL: pesquisa falhou com ${response.code}")
            }
            val page = json.decodeFromString(MalSearchPage.serializer(), response.body.string())
            return page.data.map { item -> item.node.toAnime() }
        }
    }

    private suspend fun searchThroughFunction(query: String): List<Anime> {
        val response = try {
            callFunction("search") { put("q", query) }
        } catch (e: FunctionException) {
            if (e.httpCode == HTTP_BAD_REQUEST) {
                return emptyList()
            }
            throw e
        }
        val results = json.decodeFromJsonElement(FunctionSearchResponse.serializer(), response).results
        return results.map { it.toAnime() }
    }

    // ---------- Cópia da lista (user_mal_anime) ----------

    /**
     * Lê da cópia as linhas da conta mudadas desde [updatedSince] (ISO; null = todas), de 1000 em 1000
     * (o PostgREST devolve no máximo 1000 por pedido).
     */
    suspend fun fetchCopy(userId: String, updatedSince: String?): List<Anime> = withIOContext {
        val rows = mutableListOf<UserMalAnimeRemote>()
        var firstRow = 0L
        while (true) {
            val page = supabase.postgrest["user_mal_anime"]
                .select {
                    filter {
                        eq("user_id", userId)
                        if (updatedSince != null) {
                            gte("updated_at", updatedSince)
                        }
                    }
                    order("mal_id", Order.ASCENDING)
                    range(firstRow, firstRow + COPY_PAGE_SIZE - 1)
                }
                .decodeList<UserMalAnimeRemote>()
            rows.addAll(page)

            // página incompleta = chegou ao fim
            if (page.size < COPY_PAGE_SIZE) {
                break
            }
            firstRow += COPY_PAGE_SIZE
        }
        rows.map { it.toAnime() }
    }

    // ---------- Pastas, ordem à mão e modo das abas (só Supabase; o MAL não sabe delas) ----------

    /** Lê todas as pastas da conta, de 1000 em 1000 (são poucas, por isso lê-se sempre tudo). */
    suspend fun fetchFolders(userId: String): List<AnimeFolder> = withIOContext {
        val rows = mutableListOf<UserAnimeFolderRemote>()
        var firstRow = 0L
        while (true) {
            val page = supabase.postgrest["user_anime_folders"]
                .select {
                    filter { eq("user_id", userId) }
                    order("id", Order.ASCENDING)
                    range(firstRow, firstRow + COPY_PAGE_SIZE - 1)
                }
                .decodeList<UserAnimeFolderRemote>()
            rows.addAll(page)

            // página incompleta = chegou ao fim
            if (page.size < COPY_PAGE_SIZE) {
                break
            }
            firstRow += COPY_PAGE_SIZE
        }
        rows.map { it.toFolder() }
    }

    /** Cria ou atualiza pastas. */
    suspend fun upsertFolders(userId: String, folders: List<AnimeFolder>) {
        if (folders.isEmpty()) {
            return
        }
        val rows = folders.map { folder ->
            UserAnimeFolderRemote(
                userId = userId,
                id = folder.id,
                name = folder.name,
                coverMalId = folder.coverMalId,
                sortOrder = folder.sortOrder.name,
                manualPosition = folder.manualPosition,
                manualTab = folder.manualTab?.name,
            )
        }
        withIOContext {
            supabase.postgrest["user_anime_folders"].upsert(rows) { onConflict = "id" }
        }
    }

    /** Apaga uma pasta (no Supabase, a chave estrangeira deixa os animes dela soltos). */
    suspend fun deleteFolder(userId: String, folderId: String) {
        withIOContext {
            supabase.postgrest["user_anime_folders"].delete {
                filter {
                    eq("user_id", userId)
                    eq("id", folderId)
                }
            }
        }
    }

    /** Grava a pasta e a ordem à mão de um anime na cópia (só estas colunas; o resto é do MAL). */
    suspend fun updateAnimeLayout(userId: String, anime: Anime) {
        val layout = AnimeLayoutRemote(
            folderId = anime.folderId,
            folderPosition = anime.folderPosition,
            manualPosition = anime.manualPosition,
            manualTab = anime.manualTab?.name,
        )
        withIOContext {
            supabase.postgrest["user_mal_anime"].update(layout) {
                filter {
                    eq("user_id", userId)
                    eq("mal_id", anime.malId)
                }
            }
        }
    }

    /** Modo de cada aba guardado no Supabase (as que não têm linha são RECENT). */
    suspend fun fetchTabModes(userId: String): Map<AnimeStatus, AnimeTabMode> = withIOContext {
        val rows = supabase.postgrest["user_anime_tabs"]
            .select { filter { eq("user_id", userId) } }
            .decodeList<UserAnimeTabRemote>()
        rows.mapNotNull { row ->
            AnimeStatus.tabFromRemote(row.tab)?.let { tab -> tab to AnimeTabMode.fromRemote(row.mode) }
        }.toMap()
    }

    suspend fun upsertTabMode(userId: String, tab: AnimeStatus, mode: AnimeTabMode) {
        val row = UserAnimeTabRemote(userId = userId, tab = tab.name, mode = mode.name)
        withIOContext {
            supabase.postgrest["user_anime_tabs"].upsert(row) { onConflict = "user_id,tab" }
        }
    }

    // ---------- Modelos ----------

    @Serializable
    private data class StatusResponse(
        val connected: Boolean,
        @SerialName("mal_username") val malUsername: String?,
        // Só se lê; com valor por defeito porque a versão anterior da edge function não o mandava
        @SerialName("mal_user_id") val malUserId: Long? = null,
    )

    @Serializable
    private data class SendResponse(
        val saved: Boolean,
        @SerialName("mal_synced") val malSynced: Boolean,
        @SerialName("mal_error") val malError: String? = null,
    )

    @Serializable
    private data class FunctionSearchResponse(val results: List<AnimeDetailsRemote>)

    // Ficha de um anime como a edge function a devolve na pesquisa
    @Serializable
    private data class AnimeDetailsRemote(
        @SerialName("mal_id") val malId: Long,
        val title: String,
        @SerialName("title_english") val titleEnglish: String?,
        @SerialName("cover_url") val coverUrl: String?,
        @SerialName("media_type") val mediaType: String?,
        @SerialName("num_episodes") val numEpisodes: Long?,
        @SerialName("start_date") val startDate: String?,
    ) {
        fun toAnime() = Anime(
            malId = malId,
            title = title,
            titleEnglish = titleEnglish,
            coverUrl = coverUrl,
            mediaType = mediaType,
            numEpisodes = numEpisodes,
            startDate = startDate,
            status = AnimeStatus.PLAN_TO_WATCH,
        )
    }

    @Serializable
    private data class MalSearchPage(val data: List<MalSearchItem>)

    @Serializable
    private data class MalSearchItem(val node: MalNode)

    // Nó de anime da API do MAL (só os campos pedidos em SEARCH_FIELDS)
    @Serializable
    private data class MalNode(
        val id: Long,
        val title: String,
        @SerialName("main_picture") val mainPicture: MalPicture? = null,
        @SerialName("alternative_titles") val alternativeTitles: MalAlternativeTitles? = null,
        @SerialName("num_episodes") val numEpisodes: Long? = null,
        @SerialName("media_type") val mediaType: String? = null,
        @SerialName("start_date") val startDate: String? = null,
    ) {
        fun toAnime(): Anime {
            // O MAL dá 0 episódios quando ainda não se sabe
            val knownEpisodes = numEpisodes?.takeIf { it > 0 }
            return Anime(
                malId = id,
                title = title,
                titleEnglish = alternativeTitles?.en?.takeIf { it.isNotBlank() },
                coverUrl = mainPicture?.large ?: mainPicture?.medium,
                mediaType = mediaType,
                numEpisodes = knownEpisodes,
                startDate = startDate,
                status = AnimeStatus.PLAN_TO_WATCH,
            )
        }
    }

    @Serializable
    private data class MalPicture(val medium: String? = null, val large: String? = null)

    @Serializable
    private data class MalAlternativeTitles(val en: String? = null)

    // Linha da user_mal_anime (só se lê; sem valores por defeito, como os outros modelos do Supabase)
    @Serializable
    private data class UserMalAnimeRemote(
        @SerialName("mal_id") val malId: Long,
        val title: String,
        @SerialName("title_english") val titleEnglish: String?,
        @SerialName("cover_url") val coverUrl: String?,
        @SerialName("media_type") val mediaType: String?,
        @SerialName("num_episodes") val numEpisodes: Long?,
        @SerialName("start_date") val startDate: String?,
        val status: String,
        @SerialName("episodes_watched") val episodesWatched: Long,
        val deleted: Boolean,
        @SerialName("touched_at") val touchedAt: String,
        @SerialName("folder_id") val folderId: String?,
        @SerialName("folder_position") val folderPosition: Double?,
        @SerialName("manual_position") val manualPosition: Double?,
        @SerialName("manual_tab") val manualTab: String?,
    ) {
        fun toAnime() = Anime(
            malId = malId,
            title = title,
            titleEnglish = titleEnglish,
            coverUrl = coverUrl,
            mediaType = mediaType,
            numEpisodes = numEpisodes,
            startDate = startDate,
            status = AnimeStatus.fromRemote(status),
            episodesWatched = episodesWatched,
            deleted = deleted,
            touchedAt = OffsetDateTime.parse(touchedAt).toInstant().toEpochMilli(),
            folderId = folderId,
            folderPosition = folderPosition,
            manualPosition = manualPosition,
            manualTab = manualTab?.let(AnimeStatus::tabFromRemote),
        )
    }

    // Linha da user_anime_folders (vai e vem tal e qual; sem valores por defeito para irem sempre todos)
    @Serializable
    private data class UserAnimeFolderRemote(
        @SerialName("user_id") val userId: String,
        val id: String,
        val name: String,
        @SerialName("cover_mal_id") val coverMalId: Long?,
        @SerialName("sort_order") val sortOrder: String,
        @SerialName("manual_position") val manualPosition: Double?,
        @SerialName("manual_tab") val manualTab: String?,
    ) {
        fun toFolder() = AnimeFolder(
            id = id,
            name = name,
            coverMalId = coverMalId,
            sortOrder = FolderSortOrder.fromRemote(sortOrder),
            manualPosition = manualPosition,
            manualTab = manualTab?.let(AnimeStatus::tabFromRemote),
        )
    }

    // Colunas da user_mal_anime que a app pode mudar (pasta e ordem à mão)
    @Serializable
    private data class AnimeLayoutRemote(
        @SerialName("folder_id") val folderId: String?,
        @SerialName("folder_position") val folderPosition: Double?,
        @SerialName("manual_position") val manualPosition: Double?,
        @SerialName("manual_tab") val manualTab: String?,
    )

    @Serializable
    private data class UserAnimeTabRemote(
        @SerialName("user_id") val userId: String,
        val tab: String,
        val mode: String,
    )

    private companion object {
        const val MAL_SEARCH_URL = "https://api.myanimelist.net/v2/anime"
        const val SEARCH_FIELDS = "num_episodes,media_type,start_date,alternative_titles,main_picture"
        const val SEARCH_RESULT_LIMIT = 30
        const val COPY_PAGE_SIZE = 1000L
        const val HTTP_BAD_REQUEST = 400
        const val SESSION_MARGIN_MS = 2 * 60 * 1000L
    }
}
