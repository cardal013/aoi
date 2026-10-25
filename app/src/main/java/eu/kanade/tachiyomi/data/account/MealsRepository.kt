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
import java.time.LocalTime

/**
 * Refeições (só admins): calendário partilhado por todos os admins na tabela public.admin_meals
 * (migration 20261025_admin_meals.sql). Cada dia é true (almoço em casa), false (não almoço) ou sem linha.
 * As definições do lembrete, comuns a todos os admins, estão em public.admin_meal_reminder
 * (migration 20261025_admin_meal_reminder.sql).
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

    /** Estado de um só dia: true, false ou null (por marcar). */
    suspend fun load(day: LocalDate): Boolean? = load(day, day)[day]

    /** Definições do lembrete; as de defeito se a linha ainda não existir. */
    suspend fun loadReminder(): Reminder = withIOContext {
        supabase.postgrest["admin_meal_reminder"]
            .select(columns = Columns.raw("days_before, remind_at")) { filter { eq("id", 1) } }
            .decodeList<ReminderRow>()
            .firstOrNull()
            ?.let { Reminder(it.daysBefore, LocalTime.parse(it.remindAt)) }
            ?: Reminder()
    }

    suspend fun saveReminder(reminder: Reminder) = withIOContext {
        val userId = supabase.auth.currentUserOrNull()?.id
        val row = ReminderUpsert(
            daysBefore = reminder.daysBefore,
            remindAt = reminder.time.toString(),
            updatedBy = userId,
        )
        supabase.postgrest["admin_meal_reminder"].upsert(row) { onConflict = "id" }
    }

    /** Lembrete [daysBefore] dias antes do dia, por volta de [time]. */
    data class Reminder(
        val daysBefore: Int = 1,
        val time: LocalTime = LocalTime.of(19, 0),
    )

    @Serializable
    private data class ReminderRow(
        @SerialName("days_before") val daysBefore: Int,
        @SerialName("remind_at") val remindAt: String,
    )

    @Serializable
    private data class ReminderUpsert(
        val id: Int = 1,
        @SerialName("days_before") val daysBefore: Int,
        @SerialName("remind_at") val remindAt: String,
        @SerialName("updated_by") val updatedBy: String?,
    )

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
