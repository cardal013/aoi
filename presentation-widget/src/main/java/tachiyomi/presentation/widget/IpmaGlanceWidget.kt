package tachiyomi.presentation.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.presentation.widget.util.appWidgetBackgroundRadius
import java.time.DayOfWeek
import java.time.LocalDate

class IpmaGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = IpmaGlanceWidget()
}

/**
 * Tempo do IPMA no ecrã inicial (Porto). Muda com o tamanho: 2×2 só hoje, 4×1 só a semana,
 * 4×2 hoje e a semana. Atualiza de hora a hora (updatePeriodMillis); tocar abre a previsão no site do IPMA.
 */
class IpmaGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, STRIP, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val days = withIOContext { IpmaForecast.load(context) }
        provideContent { Content(days) }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun Content(days: List<IpmaForecast.Day>) {
        val size = LocalSize.current
        val fg = ColorProvider(R.color.appwidget_on_secondary_container)
        val muted = ColorProvider(R.color.appwidget_on_surface_variant)
        val open = Intent(Intent.ACTION_VIEW, IpmaForecast.PAGE_URL.toUri())
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.appwidget_background))
                .appWidgetBackground()
                .appWidgetBackgroundRadius()
                .padding(12.dp)
                .clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                days.isEmpty() -> Text("Sem previsão do IPMA", style = TextStyle(color = muted, fontSize = 13.sp))
                size.width < STRIP.width -> Today(days.first(), fg, muted, compact = true)
                size.height < LARGE.height -> Week(days, fg, muted)
                else -> {
                    Today(days.first(), fg, muted, compact = false)
                    Spacer(GlanceModifier.height(10.dp))
                    Week(days.drop(1), fg, muted)
                }
            }
        }
    }

    @Composable
    private fun Today(day: IpmaForecast.Day, fg: ColorProvider, muted: ColorProvider, compact: Boolean) {
        if (compact) {
            Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Porto",
                        style = TextStyle(color = muted, fontSize = 12.sp),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text(IpmaForecast.emoji(day.weatherType), style = TextStyle(fontSize = 24.sp))
                }
                Text("${day.max}°", style = TextStyle(color = fg, fontSize = 34.sp, fontWeight = FontWeight.Medium))
                Text("${day.min}° · chuva ${day.rainProbability}%", style = TextStyle(color = muted, fontSize = 12.sp))
                Text(day.description, style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 1)
            }
            return
        }
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(IpmaForecast.emoji(day.weatherType), style = TextStyle(fontSize = 36.sp))
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text("${day.max}°", style = TextStyle(color = fg, fontSize = 32.sp, fontWeight = FontWeight.Medium))
                Text(
                    "mín ${day.min}° · ${day.description}",
                    style = TextStyle(color = muted, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Porto", style = TextStyle(color = muted, fontSize = 12.sp))
                Text("chuva ${day.rainProbability}%", style = TextStyle(color = muted, fontSize = 12.sp))
                Text("vento ${day.windDirection}", style = TextStyle(color = muted, fontSize = 12.sp))
            }
        }
    }

    @Composable
    private fun Week(days: List<IpmaForecast.Day>, fg: ColorProvider, muted: ColorProvider) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            days.take(5).forEach { day ->
                Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayName(day.date), style = TextStyle(color = muted, fontSize = 12.sp))
                    Text(IpmaForecast.emoji(day.weatherType), style = TextStyle(fontSize = 20.sp))
                    Text(
                        "${day.max}°",
                        style = TextStyle(color = fg, fontSize = SMALL_TEXT, fontWeight = FontWeight.Medium),
                    )
                    Text("${day.min}°", style = TextStyle(color = muted, fontSize = SMALL_TEXT))
                }
            }
        }
    }

    private fun dayName(date: LocalDate): String = if (date == LocalDate.now()) {
        "Hoje"
    } else {
        when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "Seg"
            DayOfWeek.TUESDAY -> "Ter"
            DayOfWeek.WEDNESDAY -> "Qua"
            DayOfWeek.THURSDAY -> "Qui"
            DayOfWeek.FRIDAY -> "Sex"
            DayOfWeek.SATURDAY -> "Sáb"
            DayOfWeek.SUNDAY -> "Dom"
        }
    }

    private companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val STRIP = DpSize(250.dp, 70.dp)
        val LARGE = DpSize(250.dp, 150.dp)
        val SMALL_TEXT: TextUnit = 13.sp
    }
}
