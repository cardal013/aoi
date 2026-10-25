package eu.kanade.tachiyomi.data.account

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Lembrete das Refeições, agendado pelo [MealReminder]: se o dia [KEY_DAY] estiver marcado, mostra
 * "Amanhã o Afonso come em casa" ou "Amanhã o Afonso não come em casa". Dia por marcar não tem lembrete.
 * No fim agenda o seguinte com as definições que estiverem na cloud.
 */
class MealReminderJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var mealsRepository: MealsRepository

    @Inject lateinit var adminRepository: AdminRepository

    @Inject lateinit var mealReminder: MealReminder

    override suspend fun doWork(): Result {
        graph.inject(this)
        val day = inputData.getString(KEY_DAY)?.let(LocalDate::parse) ?: return Result.failure()

        val reminder = try {
            // Sem sessão ou já não é admin: não avisa nem volta a agendar (a app agenda outra vez se voltar a ser)
            if (!adminRepository.isAdminNow()) return Result.success()

            val today = LocalDate.now()
            if (day.isAfter(today)) {
                mealsRepository.load(day)?.let { atHome -> showNotification(context, day, atHome, today) }
            }
            mealsRepository.loadReminder()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Refeições: o lembrete de $day falhou" }
            if (runAttemptCount < MAX_RETRIES) return Result.retry()
            mealReminder.cached
        }

        mealReminder.scheduleNext(reminder)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "MealReminder"
        const val KEY_DAY = "day"
        private const val MAX_RETRIES = 6

        private val PORTUGUESE = Locale.forLanguageTag("pt-PT")
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE (dd/MM)", PORTUGUESE)

        // Mais claros que os do calendário, para se lerem no fundo claro e no escuro da barra de notificações
        private val AT_HOME = Color.rgb(0x43, 0xA0, 0x47)
        private val NOT_AT_HOME = Color.rgb(0xE5, 0x39, 0x35)

        /** "Amanhã o Afonso come em casa" (ou "não come"), com o "come"/"não" a negrito e a cor. */
        fun showNotification(context: Context, day: LocalDate, atHome: Boolean, today: LocalDate) {
            val daysAway = ChronoUnit.DAYS.between(today, day)
            val whenText = if (daysAway == 1L) {
                "Amanhã"
            } else {
                day.format(DAY_FORMAT).replaceFirstChar { it.titlecase(PORTUGUESE) }
            }
            val text = buildSpannedString {
                append("$whenText o Afonso ")
                if (atHome) {
                    bold { color(AT_HOME) { append("come") } }
                } else {
                    bold { color(NOT_AT_HOME) { append("não") } }
                    append(" come")
                }
                append(" em casa")
            }
            val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(
                    context,
                    0,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

            // O Android tira o negrito e a cor ao texto normal da notificação; num layout próprio ficam
            val content = RemoteViews(context.packageName, R.layout.meal_reminder_notification).apply {
                setTextViewText(R.id.meal_reminder_text, text)
            }

            context.notify(Notifications.ID_MEAL_REMINDER, Notifications.CHANNEL_MEALS) {
                setSmallIcon(R.drawable.ic_mihon)
                setContentTitle(context.stringResource(MR.strings.more_meals))
                setContentText(text.toString())
                setCustomContentView(content)
                setCustomBigContentView(content)
                setStyle(NotificationCompat.DecoratedCustomViewStyle())
                setPriority(NotificationCompat.PRIORITY_HIGH)
                setCategory(NotificationCompat.CATEGORY_REMINDER)
                setContentIntent(openApp)
                setAutoCancel(true)
            }
        }
    }
}
