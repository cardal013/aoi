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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

@Inject
@SingleIn(AppScope::class)
class AccountRepository {

    private val refreshedUsername = MutableStateFlow<String?>(null)

    val sessionFlow: Flow<String?> = combine(
        supabase.auth.sessionStatus,
        refreshedUsername,
    ) { status, refreshed ->
        if (status is SessionStatus.Authenticated) {
            // Priority:
            // 1. Manually refreshed username from profiles table
            // 2. Username from user_metadata (set during signup or updated via web)
            // 3. Fallback to email prefix (legacy/initial state)
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
        logcat(LogPriority.INFO) { "AOI_ACCOUNT: Starting signup for $trimmedUsername as $internalEmail" }

        return try {
            logcat(LogPriority.DEBUG) { "AOI_ACCOUNT: Calling supabase.auth.signUp..." }
            val authResponse = supabase.auth.signUpWith(Email) {
                this.email = internalEmail
                this.password = password
                // Armazenar o username real nos metadados para o site e futura compatibilidade
                data = buildJsonObject {
                    put("username", trimmedUsername)
                }
            }

            val uid = authResponse?.id
            logcat(LogPriority.INFO) { "AOI_ACCOUNT: Auth response received. UID: $uid" }

            if (uid != null) {
                logcat(LogPriority.DEBUG) { "AOI_ACCOUNT: Inserting into profiles table..." }
                // Inserir na tabela profiles do Supabase
                try {
                    supabase.postgrest["profiles"].insert(
                        Profile(id = uid, username = trimmedUsername),
                    )
                    logcat(LogPriority.INFO) { "AOI_ACCOUNT: Profile insert success for $trimmedUsername" }
                    Result.success(Unit)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: Profile insert FAILED" }
                    Result.failure(e)
                }
            } else {
                logcat(LogPriority.WARN) { "AOI_ACCOUNT: UID is null after signup" }
                Result.failure(Exception("Failed to get User ID"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: Auth signUpWith FAILED" }
            Result.failure(e)
        }
    }

    suspend fun login(username: String, password: String): Result<Unit> {
        val internalEmail = formatInternalEmail(username)
        logcat(LogPriority.INFO) { "AOI_ACCOUNT: Starting login for $username" }

        return try {
            supabase.auth.signInWith(Email) {
                this.email = internalEmail
                this.password = password
            }
            logcat(LogPriority.INFO) { "AOI_ACCOUNT: Login success for $username" }
            // Fetch fresh profile data after login
            fetchUsername()
            Result.success(Unit)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: Login error" }
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
                logcat(LogPriority.INFO) { "AOI_ACCOUNT: Fetched fresh username: ${profile.username}" }
                refreshedUsername.value = profile.username
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: Failed to fetch username" }
        }
    }

    suspend fun logout() {
        logcat(LogPriority.INFO) { "AOI_ACCOUNT: Logging out" }
        refreshedUsername.value = null
        supabase.auth.signOut()
    }

    @Serializable
    private data class Profile(
        val id: String? = null,
        val username: String,
    )
}
