package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

@Inject
@SingleIn(AppScope::class)
class AccountRepository {

    val sessionFlow: Flow<String?> = supabase.auth.sessionStatus
        .map { status ->
            if (status is SessionStatus.Authenticated) {
                // Devolvemos o username extraído do email interno para a UI
                status.session.user?.email?.removePrefix("usr_")?.removeSuffix("@aoi-app.com")
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
        logcat(LogPriority.INFO) { "AOI_ACCOUNT: Starting signup for $username as $internalEmail" }

        return try {
            val authResponse = supabase.auth.signUpWith(Email) {
                this.email = internalEmail
                this.password = password
            }

            val uid = authResponse?.id
            if (uid != null) {
                // Inserir na tabela profiles do Supabase
                supabase.postgrest["profiles"].insert(
                    Profile(id = uid, username = username.trim())
                )
                logcat(LogPriority.INFO) { "AOI_ACCOUNT: Signup success for $username" }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to get User ID"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: Signup error" }
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
            Result.success(Unit)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "AOI_ACCOUNT: Login error" }
            Result.failure(e)
        }
    }

    suspend fun logout() {
        logcat(LogPriority.INFO) { "AOI_ACCOUNT: Logging out" }
        supabase.auth.signOut()
    }

    @Serializable
    private data class Profile(
        val id: String,
        val username: String,
    )
}
