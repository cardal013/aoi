package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tachiyomi.core.common.util.lang.withIOContext
import java.time.LocalDate

/**
 * Refeições (só admins): calendário partilhado por todos os admins na tabela public.admin_meals
 * (migration 20261025_admin_meals.sql). Cada dia é true (almoço em casa), false (não almoço) ou sem linha.
 */
@Inject
@SingleIn(AppScope::class)
class MealsRepository {

    /** Dias marcados entre [from] e [to] (inclusive). Um mês tem no máximo 31 linhas, não é preciso paginar. */
    suspend fun load(from: LocalDate, to: LocalDate): Map<LocalDate, Boolean> = withIOContext {
        supabase.postgrest["admin_meals"]
            .select(columns = Columns.raw("day, at_home")) {
                filter {
                    gte("day", from.toString())
                    lte("day", to.toString())
                }
            }
            .decodeList<MealRow>()
            .associate { LocalDate.parse(it.day) to it.atHome }
    }

    /** Marca [day]; null apaga a marcação. */
    suspend fun set(day: LocalDate, atHome: Boolean?) = withIOContext {
        val table = supabase.postgrest["admin_meals"]
        if (atHome == null) {
            table.delete { filter { eq("day", day.toString()) } }
        } else {
            val userId = supabase.auth.currentUserOrNull()?.id
            table.upsert(MealUpsert(day.toString(), atHome, userId)) { onConflict = "day" }
        }
    }

    @Serializable
    private data class MealRow(
        val day: String,
        @SerialName("at_home") val atHome: Boolean,
    )

    @Serializable
    private data class MealUpsert(
        val day: String,
        @SerialName("at_home") val atHome: Boolean,
        @SerialName("updated_by") val updatedBy: String?,
    )
}
