package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.jsonMime
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import java.io.IOException
import java.time.OffsetDateTime

/**
 * Ecrã Users (só admins, só na app): lista as contas, dá ou tira admin e muda a palavra-passe.
 * As duas primeiras são funções do Supabase que só correm se quem chama for admin (admin_list_users,
 * admin_set_admin, migration 20261024_users_admin.sql); a palavra-passe vai pela edge function
 * `admin-set-password`, porque precisa da API de admin do Auth (service role).
 */
@Inject
@SingleIn(AppScope::class)
class UsersRepository(
    network: NetworkHelper,
    private val json: Json,
) {

    private val client = network.client

    /** Uma conta do Aoi, como o admin a vê. [createdAt] em ms. */
    data class User(
        val userId: String,
        val username: String?,
        val createdAt: Long,
        val isAdmin: Boolean,
    )

    /** Erro de uma das funções: [errorCode] é o código que o servidor devolveu (ex.: target_is_admin). */
    class UsersException(val errorCode: String) : Exception("Users: $errorCode")

    /** Conta com sessão (para o ecrã não deixar tirar o admin a si próprio). */
    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    /**
     * Contas cujo username tem [search] (vazio = todas), por username, de 1000 em 1000
     * (o PostgREST devolve no máximo 1000 por pedido).
     */
    suspend fun listUsers(search: String): List<User> = withIOContext {
        val parameters = buildJsonObject { put("search", search.trim()) }
        val users = mutableListOf<User>()
        var firstRow = 0L
        while (true) {
            val page = supabase.postgrest
                .rpc("admin_list_users", parameters) { range(firstRow, firstRow + PAGE_SIZE - 1) }
                .decodeList<UserRow>()
            users.addAll(page.map { it.toUser() })

            // página incompleta = chegou ao fim
            if (page.size < PAGE_SIZE) {
                break
            }
            firstRow += PAGE_SIZE
        }
        users
    }

    /** Dá ([makeAdmin] = true) ou tira o admin à conta [userId]. O servidor recusa tirar o próprio. */
    suspend fun setAdmin(userId: String, makeAdmin: Boolean) {
        val parameters = buildJsonObject {
            put("target_user_id", userId)
            put("make_admin", makeAdmin)
        }
        withIOContext { supabase.postgrest.rpc("admin_set_admin", parameters) }
    }

    /**
     * Muda a palavra-passe da conta [userId] para [newPassword] (mínimo 6 caracteres; não pode ser outro admin).
     * A conta sai da sessão em todos os aparelhos. Lança [UsersException] com o código do servidor se recusar
     * e [IOException] sem rede.
     */
    suspend fun setPassword(userId: String, newPassword: String) {
        val accessToken = supabase.auth.currentSessionOrNull()?.accessToken ?: throw IOException("Sem sessão")
        val body = buildJsonObject {
            put("user_id", userId)
            put("new_password", newPassword)
        }
        val headers = Headers.Builder()
            .add("Authorization", "Bearer $accessToken")
            .add("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()
        val request = POST(
            url = "${BuildConfig.SUPABASE_URL}/functions/v1/admin-set-password",
            headers = headers,
            body = body.toString().toRequestBody(jsonMime),
        )

        withIOContext {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    val responseText = response.body.string()
                    val errorJson = runCatching { json.parseToJsonElement(responseText) as JsonObject }.getOrNull()
                    val errorCode = errorJson?.get("error")?.jsonPrimitive?.content ?: "http_${response.code}"
                    throw UsersException(errorCode)
                }
            }
        }
    }

    // Linha devolvida por admin_list_users
    @Serializable
    private data class UserRow(
        @SerialName("user_id") val userId: String,
        val username: String?,
        @SerialName("created_at") val createdAt: String,
        @SerialName("is_admin") val isAdmin: Boolean,
    ) {
        fun toUser() = User(
            userId = userId,
            username = username,
            createdAt = OffsetDateTime.parse(createdAt).toInstant().toEpochMilli(),
            isAdmin = isAdmin,
        )
    }

    private companion object {
        const val PAGE_SIZE = 1000L
    }
}
