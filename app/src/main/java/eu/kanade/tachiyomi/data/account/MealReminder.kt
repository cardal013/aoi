package eu.kanade.tachiyomi.data.account

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Agenda o lembrete das Refeições neste telemóvel, só enquanto a conta for admin: um [MealReminderJob]
 * para a próxima vez que forem as horas do lembrete, que fala do dia [MealsRepository.Reminder.daysBefore]
 * dias depois. As definições vêm da cloud (comuns a todos os admins) e ficam guardadas aqui para agendar sem rede.
 */
@Inject
@SingleIn(AppScope::class)
class MealReminder(
    private val context: Context,
    private val mealsRepository: MealsRepository,
    private val adminRepository: AdminRepository,
    preferenceStore: PreferenceStore,
) {

    private val cachedDaysBefore = preferenceStore.getInt("aoi_meal_reminder_days_before", 1)
    private val cachedMinuteOfDay = preferenceStore.getInt("aoi_meal_reminder_minute_of_day", 19 * 60)

    val cached: MealsRepository.Reminder
        get() = MealsRepository.Reminder(
            daysBefore = cachedDaysBefore.get(),
            time = LocalTime.ofSecondOfDay(cachedMinuteOfDay.get() * 60L),
        )

    /** Ao abrir a app: agenda se a conta for admin, cancela se não for. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            adminRepository.isAdmin.collectLatest { isAdmin ->
                if (isAdmin) {
                    schedule(cached, ExistingWorkPolicy.KEEP)
                    refreshFromCloud()
                } else {
                    context.workManager.cancelUniqueWork(MealReminderJob.WORK_NAME)
                }
            }
        }
    }

    /** Definições mudadas neste telemóvel (já gravadas na cloud). */
    fun update(reminder: MealsRepository.Reminder) {
        if (reminder == cached) return
        saveCache(reminder)
        schedule(reminder, ExistingWorkPolicy.REPLACE)
    }

    /** Depois de um lembrete: agenda o seguinte. Substitui o trabalho que está a correr, por isso tem de ser o último passo. */
    fun scheduleNext(reminder: MealsRepository.Reminder) {
        saveCache(reminder)
        schedule(reminder, ExistingWorkPolicy.REPLACE)
    }

    private suspend fun refreshFromCloud() {
        try {
            update(mealsRepository.loadReminder())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Refeições: não deu para ler as definições do lembrete" }
        }
    }

    private fun saveCache(reminder: MealsRepository.Reminder) {
        cachedDaysBefore.set(reminder.daysBefore)
        cachedMinuteOfDay.set(reminder.time.toSecondOfDay() / 60)
    }

    private fun schedule(reminder: MealsRepository.Reminder, policy: ExistingWorkPolicy) {
        val now = LocalDateTime.now()
        var trigger = now.toLocalDate().atTime(reminder.time)
        if (!trigger.isAfter(now)) trigger = trigger.plusDays(1)
        val day = trigger.toLocalDate().plusDays(reminder.daysBefore.toLong())

        val request = OneTimeWorkRequestBuilder<MealReminderJob>()
            .setInitialDelay(Duration.between(now, trigger).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf(MealReminderJob.KEY_DAY to day.toString()))
            .build()
        context.workManager.enqueueUniqueWork(MealReminderJob.WORK_NAME, policy, request)
    }
}
