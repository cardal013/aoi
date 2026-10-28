package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat

/**
 * Contas admin (tabela public.admins, pelo id da conta, por isso mudar o username não muda nada)
 * e os dados do Admin (Savearth e Definições) na tabela public.admin_data.
 */
@Inject
@SingleIn(AppScope::class)
class AdminRepository(
    private val preferenceStore: PreferenceStore,
) {

    /** Para os envios acabarem mesmo depois de sair do ecrã Admin. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Se a conta com sessão é admin. Começa pelo último valor conhecido, para o botão aparecer logo sem rede. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isAdmin: Flow<Boolean> = if (BuildConfig.SUPABASE_URL.isBlank()) {
        flowOf(false)
    } else {
        supabase.auth.sessionStatus
            .map { (it as? SessionStatus.Authenticated)?.session?.user?.id }
            .distinctUntilChanged()
            .transformLatest { userId ->
                if (userId == null) {
                    emit(false)
                    return@transformLatest
                }
                val cached = preferenceStore.getBoolean("aoi_is_admin_$userId", false)
                emit(cached.get())
                try {
                    val admin = fetchIsAdmin(userId)
                    cached.set(admin)
                    emit(admin)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Admin: não deu para confirmar se a conta é admin" }
                }
            }
            .distinctUntilChanged()
    }

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    /** Pergunta à cloud se a conta com sessão é admin agora (para os trabalhos em segundo plano). */
    suspend fun isAdminNow(): Boolean {
        val userId = currentUserId() ?: return false
        return fetchIsAdmin(userId)
    }

    private suspend fun fetchIsAdmin(userId: String): Boolean =
        supabase.postgrest["admins"]
            .select(columns = Columns.raw("user_id")) { filter { eq("user_id", userId) } }
            .decodeList<AdminRow>()
            .isNotEmpty()

    /**
     * Todas as chaves guardadas na cloud, como um objeto JSON { chave: valor }. Sem a chave "meals",
     * as Refeições vêm da tabela antiga admin_meals (partilhada pelos admins até à 0.28.0).
     */
    suspend fun loadData(userId: String): JsonObject {
        val rows = supabase.postgrest["admin_data"]
            .select(columns = Columns.raw("key, value")) { filter { eq("user_id", userId) } }
            .decodeList<AdminDataRow>()
        val data = rows.associate { it.key to (it.value ?: JsonNull) }
        if (MEALS_KEY in data) return JsonObject(data)
        return JsonObject(data + (MEALS_KEY to legacyMeals()))
    }

    private suspend fun legacyMeals(): JsonObject = try {
        val rows = supabase.postgrest["admin_meals"]
            .select(columns = Columns.raw("day, at_home"))
            .decodeList<LegacyMealRow>()
        JsonObject(rows.associate { it.day to JsonPrimitive(it.atHome) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Admin: não deu para ler as Refeições antigas" }
        JsonObject(emptyMap())
    }

    suspend fun save(userId: String, key: String, value: JsonElement) {
        supabase.postgrest["admin_data"].upsert(AdminDataUpsert(userId, key, value)) {
            onConflict = "user_id,key"
        }
    }

    @Serializable
    private data class AdminRow(@SerialName("user_id") val userId: String)

    @Serializable
    private data class LegacyMealRow(val day: String, @SerialName("at_home") val atHome: Boolean)

    @Serializable
    private data class AdminDataRow(val key: String, val value: JsonElement? = null)

    @Serializable
    private data class AdminDataUpsert(
        @SerialName("user_id") val userId: String,
        val key: String,
        val value: JsonElement,
    )

    private companion object {
        const val MEALS_KEY = "meals"
    }
}
