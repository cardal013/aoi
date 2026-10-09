package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// Os logs daqui não levam username nem email interno: o logcat pode ser lido por outras ferramentas
@Inject
@SingleIn(AppScope::class)
class AccountRepository {

    private val refreshedUsername = MutableStateFlow<String?>(null)

    val sessionFlow: Flow<String?> = combine(
        supabase.auth.sessionStatus,
        refreshedUsername,
    ) { status, refreshed ->
        if (status is SessionStatus.Authenticated) {
            // Prioridade: profiles (lido no login), user_metadata (signup ou site), prefixo do email interno
            refreshed
                ?: status.session.user?.userMetadata?.get("username")?.jsonPrimitive?.contentOrNull
                ?: status.session.user?.email?.removePrefix("usr_")?.removeSuffix("@aoi-app.com")
        } else {
            null
        }
    }

    private fun formatInternalEmail(username: String): String {
        val normalized = username.trim().lowercase().filter { it.isLetterOrDigit() }
        return "usr_$normalized@aoi-app.com"
    }

    suspend fun signUp(username: String, password: String): Result<Unit> {
        val internalEmail = formatInternalEmail(username)
        val trimmedUsername = username.trim()

        return try {
            val authResponse = supabase.auth.signUpWith(Email) {
                this.email = internalEmail
                this.password = password
                // O username real fica nos metadados para o site
                data = buildJsonObject {
                    put("username", trimmedUsername)
                }
            }

            val uid = authResponse?.id
            if (uid != null) {
                try {
                    supabase.postgrest["profiles"].insert(
                        Profile(id = uid, username = trimmedUsername),
                    )
                    Result.success(Unit)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: falhou o insert em profiles" }
                    Result.failure(e)
                }
            } else {
                logcat(LogPriority.WARN) { "AOI_ACCOUNT: signup sem UID" }
                Result.failure(Exception("Failed to get User ID"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: signup falhou" }
            Result.failure(e)
        }
    }

    suspend fun login(username: String, password: String): Result<Unit> {
        val internalEmail = formatInternalEmail(username)
        return try {
            supabase.auth.signInWith(Email) {
                this.email = internalEmail
                this.password = password
            }
            fetchUsername()
            Result.success(Unit)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: login falhou" }
            Result.failure(e)
        }
    }

    suspend fun fetchUsername() {
        val user = supabase.auth.currentUserOrNull() ?: return
        try {
            val profile = supabase.postgrest["profiles"]
                .select(columns = Columns.list("username")) {
                    filter { eq("id", user.id) }
                }
                .decodeSingleOrNull<Profile>()

            if (profile != null) {
                refreshedUsername.value = profile.username
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: falhou a leitura do username" }
        }
    }

    suspend fun logout() {
        refreshedUsername.value = null
        supabase.auth.signOut()
    }

    @Serializable
    private data class Profile(
        val id: String? = null,
        val username: String,
    )
}
