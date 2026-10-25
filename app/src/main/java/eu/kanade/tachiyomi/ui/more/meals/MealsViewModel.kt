package eu.kanade.tachiyomi.ui.more.meals

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.account.MealReminder
import eu.kanade.tachiyomi.data.account.MealsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Refeições (só admins): um mês de cada vez; tocar num dia passa de vazio a verde (almoço em casa),
 * a vermelho (não almoço) e de volta a vazio. Muda logo no ecrã e volta atrás se a cloud recusar.
 * Por baixo, as definições do lembrete (comuns a todos os admins), que reagendam o [MealReminder].
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class MealsViewModel(
    private val mealsRepository: MealsRepository,
    private val mealReminder: MealReminder,
) : ViewModel() {

    private val mutableState = MutableStateFlow(State(month = YearMonth.now(), reminder = mealReminder.cached))
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var savedReminder = mealReminder.cached
    private val reminderMutex = Mutex()

    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = mutableEvents.asSharedFlow()

    init {
        viewModelScope.launchIO {
            state.map { it.month }
                .distinctUntilChanged()
                .collectLatest(::load)
        }
        viewModelScope.launchIO { loadReminder() }
    }

    fun previousMonth() {
        mutableState.update { it.copy(month = it.month.minusMonths(1)) }
    }

    fun nextMonth() {
        mutableState.update { it.copy(month = it.month.plusMonths(1)) }
    }

    fun retry() {
        viewModelScope.launchIO { load(state.value.month) }
        viewModelScope.launchIO { loadReminder() }
    }

    private suspend fun load(month: YearMonth) {
        mutableState.update { it.copy(isLoading = true, hasError = false) }
        try {
            val days = mealsRepository.load(month.atDay(1), month.atEndOfMonth())
            mutableState.update { current ->
                val otherMonths = current.days.filterKeys { YearMonth.from(it) != month }
                current.copy(days = otherMonths + days, isLoading = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Refeições: não deu para carregar $month" }
            mutableState.update { it.copy(isLoading = false, hasError = true) }
        }
    }

    fun toggle(day: LocalDate) {
        val previous = state.value.days[day]
        val next = when (previous) {
            null -> true
            true -> false
            false -> null
        }
        setDay(day, next)
        viewModelScope.launchIO {
            try {
                mealsRepository.set(day, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Refeições: não deu para guardar $day" }
                setDay(day, previous)
                mutableEvents.tryEmit(Event.SaveFailed)
            }
        }
    }

    private suspend fun loadReminder() {
        try {
            val reminder = mealsRepository.loadReminder()
            savedReminder = reminder
            mutableState.update { it.copy(reminder = reminder) }
            mealReminder.update(reminder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Refeições: não deu para ler o lembrete" }
        }
    }

    fun setDaysBefore(daysBefore: Int) {
        setReminder(state.value.reminder.copy(daysBefore = daysBefore.coerceIn(MIN_DAYS_BEFORE, MAX_DAYS_BEFORE)))
    }

    fun setReminderTime(time: LocalTime) {
        setReminder(state.value.reminder.copy(time = time))
    }

    /** Muda logo no ecrã; grava sempre o valor mais recente, um de cada vez, e volta atrás se a cloud recusar. */
    private fun setReminder(reminder: MealsRepository.Reminder) {
        mutableState.update { it.copy(reminder = reminder) }
        viewModelScope.launchIO {
            reminderMutex.withLock {
                val latest = state.value.reminder
                if (latest == savedReminder) return@withLock
                try {
                    mealsRepository.saveReminder(latest)
                    savedReminder = latest
                    mealReminder.update(latest)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Refeições: não deu para guardar o lembrete" }
                    mutableState.update { it.copy(reminder = savedReminder) }
                    mutableEvents.tryEmit(Event.ReminderSaveFailed)
                }
            }
        }
    }

    private fun setDay(day: LocalDate, atHome: Boolean?) {
        mutableState.update {
            it.copy(days = if (atHome == null) it.days - day else it.days + (day to atHome))
        }
    }

    enum class Event {
        SaveFailed,
        ReminderSaveFailed,
    }

    @Immutable
    data class State(
        val month: YearMonth,
        val days: Map<LocalDate, Boolean> = emptyMap(),
        val isLoading: Boolean = true,
        val hasError: Boolean = false,
        val reminder: MealsRepository.Reminder,
    )

    companion object {
        const val MIN_DAYS_BEFORE = 1
        const val MAX_DAYS_BEFORE = 7
    }
}
