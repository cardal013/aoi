package eu.kanade.tachiyomi.ui.more.meals

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.account.MealsRepository
import eu.kanade.tachiyomi.util.system.toast
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ChevronLeft
import mihon.icons.materialsymbols.automirroredrounded.ChevronRight
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Remove
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Ecrã Refeições, aberto no More (só para admins). Calendário de um mês, partilhado por todos os admins:
 * verde = almoço em casa, vermelho = não almoço, vazio = por marcar. Em português, porque é para a família.
 */
class MealsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<MealsViewModel>()
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) {
            viewModel.events.collect { event ->
                when (event) {
                    MealsViewModel.Event.SaveFailed -> context.toast(MR.strings.meals_save_failed)
                    MealsViewModel.Event.ReminderSaveFailed -> context.toast(MR.strings.meals_reminder_failed)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.more_meals),
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = viewModel::retry) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Refresh,
                                contentDescription = stringResource(MR.strings.action_retry),
                            )
                        }
                    },
                )
            },
        ) { contentPadding ->
            if (state.hasError) {
                EmptyScreen(
                    stringRes = MR.strings.meals_error,
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_retry,
                            icon = MaterialSymbols.Rounded.Refresh,
                            onClick = viewModel::retry,
                        ),
                    ),
                )
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                MonthHeader(
                    month = state.month,
                    onPrevious = viewModel::previousMonth,
                    onNext = viewModel::nextMonth,
                )
                MonthGrid(
                    month = state.month,
                    days = state.days,
                    onClickDay = viewModel::toggle,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Legend(modifier = Modifier.padding(16.dp))
                HorizontalDivider()
                ReminderSection(
                    reminder = state.reminder,
                    onDaysBeforeChange = viewModel::setDaysBefore,
                    onTimeChange = viewModel::setReminderTime,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(imageVector = MaterialSymbols.AutoMirroredRounded.ChevronLeft, contentDescription = null)
        }
        Text(
            text = month.format(MONTH_FORMAT).replaceFirstChar { it.titlecase(PORTUGUESE) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext) {
            Icon(imageVector = MaterialSymbols.AutoMirroredRounded.ChevronRight, contentDescription = null)
        }
    }
}

/** Semana de segunda a domingo; os dias antes do dia 1 ficam em branco. */
@Composable
private fun MonthGrid(
    month: YearMonth,
    days: Map<LocalDate, Boolean>,
    onClickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
    val cells = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map(month::atDay)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { dayOfWeek ->
                Text(
                    text = dayOfWeek.getDisplayName(TextStyle.NARROW, PORTUGUESE),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (day != null) {
                            DayCell(
                                day = day,
                                atHome = days[day],
                                isToday = day == today,
                                onClick = { onClickDay(day) },
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    atHome: Boolean?,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val (background, content) = when (atHome) {
        true -> AT_HOME to Color.White
        false -> NOT_AT_HOME to Color.White
        null -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = CELL_SHAPE,
        color = background,
        contentColor = content,
        border = if (isToday) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else null,
            )
        }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendItem(color = AT_HOME, text = stringResource(MR.strings.meals_at_home))
        LegendItem(color = NOT_AT_HOME, text = stringResource(MR.strings.meals_not_at_home))
        Text(
            text = stringResource(MR.strings.meals_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Quando cai o lembrete: quantos dias antes e a que horas (comum a todos os admins). */
@Composable
private fun ReminderSection(
    reminder: MealsRepository.Reminder,
    onDaysBeforeChange: (Int) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val days = pluralStringResource(MR.plurals.meals_reminder_days, reminder.daysBefore, reminder.daysBefore)
    val time = reminder.time.format(TIME_FORMAT)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(MR.strings.meals_reminder_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(MR.strings.meals_reminder_summary, days, time),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(MR.strings.meals_reminder_days_before),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            IconButton(
                onClick = { onDaysBeforeChange(reminder.daysBefore - 1) },
                enabled = reminder.daysBefore > MealsViewModel.MIN_DAYS_BEFORE,
            ) {
                Icon(imageVector = MaterialSymbols.Rounded.Remove, contentDescription = null)
            }
            Text(
                text = days,
                modifier = Modifier.widthIn(min = 64.dp),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = { onDaysBeforeChange(reminder.daysBefore + 1) },
                enabled = reminder.daysBefore < MealsViewModel.MAX_DAYS_BEFORE,
            ) {
                Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> onTimeChange(LocalTime.of(hour, minute)) },
                        reminder.time.hour,
                        reminder.time.minute,
                        true,
                    ).show()
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.meals_reminder_time),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = time,
                modifier = Modifier.widthIn(min = 64.dp).padding(end = 48.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val PORTUGUESE = Locale.forLanguageTag("pt-PT")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL 'de' yyyy", PORTUGUESE)
private val AT_HOME = Color(0xFF2E7D32)
private val NOT_AT_HOME = Color(0xFFC62828)
private val CELL_SHAPE = RoundedCornerShape(10.dp)
