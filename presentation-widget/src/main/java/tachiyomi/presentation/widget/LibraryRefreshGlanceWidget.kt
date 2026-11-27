package tachiyomi.presentation.widget

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil3.annotation.ExperimentalCoilApi
import coil3.asDrawable
import coil3.executeBlocking
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.transform.RoundedCornersTransformation
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.flow.map
import mihon.core.metro.metroGraph
import mihon.presentation.widget.di.PresentationWidgetGraph
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.ReadingStatus
import tachiyomi.domain.manga.model.categoryId
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.widget.components.CoverHeight
import tachiyomi.presentation.widget.components.CoverWidth
import tachiyomi.presentation.widget.components.LockedWidget
import tachiyomi.presentation.widget.components.UpdatesMangaCover
import tachiyomi.presentation.widget.util.appWidgetBackgroundRadius

class LibraryRefreshGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = LibraryRefreshGlanceWidget()
}

/**
 * Aoi: botões para atualizar cada estado (pela ordem das abas da biblioteca), ↻ para atualizar tudo e as capas
 * dos mangas com capítulos novos por ler. Quanto maior o widget, mais capas.
 */
@HasMemberInjections
class LibraryRefreshGlanceWidget : GlanceAppWidget() {

    @Inject private lateinit var getUpdates: GetUpdates

    @Inject private lateinit var preferences: SecurityPreferences

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.metroGraph<PresentationWidgetGraph>().inject(this)
        val locked = preferences.useAuthenticator.get()
        val modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.appwidget_background))
            .appWidgetBackground()
            .appWidgetBackgroundRadius()
            .padding(10.dp)

        val manager = GlanceAppWidgetManager(context)
        val maxCovers = manager.getGlanceIds(javaClass)
            .flatMap { manager.getAppWidgetSizes(it) }
            .maxOfOrNull { coverGrid(it.width.value, it.height.value).let { (r, c) -> r * c } }
            ?: 10

        provideContent {
            val fg = ColorProvider(R.color.appwidget_on_secondary_container)
            if (locked) {
                LockedWidget(foreground = fg, modifier = modifier)
                return@provideContent
            }
            val flow = remember {
                getUpdates
                    .subscribe(false, BaseUpdatesGridGlanceWidget.DateLimit.toEpochMilliseconds())
                    .map { it.prepareData(context, maxCovers) }
            }
            val data by flow.collectAsState(initial = null)
            Content(data, modifier)
        }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun Content(data: List<NewChapters>?, modifier: GlanceModifier) {
        val context = LocalContext.current
        val fg = ColorProvider(R.color.appwidget_on_secondary_container)
        val muted = ColorProvider(R.color.appwidget_on_surface_variant)
        Column(modifier = modifier) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(MR.strings.app_name),
                    style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(GlanceModifier.defaultWeight())
                if (data != null && data.isNotEmpty()) {
                    Text(
                        text = "${data.sumOf { it.count }} new",
                        style = TextStyle(color = muted, fontSize = 12.sp),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                }
                Box(
                    modifier = GlanceModifier
                        .size(28.dp)
                        .background(ImageProvider(R.drawable.appwidget_button_background))
                        .clickable(actionSendBroadcast(refreshIntent(context, null))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.appwidget_ic_refresh),
                        contentDescription = stringResource(MR.strings.action_update_library),
                        modifier = GlanceModifier.size(18.dp),
                    )
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                ReadingStatus.entries.forEachIndexed { index, status ->
                    if (index > 0) Spacer(GlanceModifier.width(6.dp))
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .height(36.dp)
                            .background(ImageProvider(R.drawable.appwidget_button_background))
                            .clickable(actionSendBroadcast(refreshIntent(context, status.categoryId))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(status.label()),
                            style = TextStyle(color = fg, fontSize = 12.sp, textAlign = TextAlign.Center),
                            maxLines = 1,
                        )
                    }
                }
            }
            Covers(data, fg)
        }
    }

    @Composable
    private fun Covers(data: List<NewChapters>?, fg: ColorProvider) {
        val size = LocalSize.current
        val (rows, columns) = coverGrid(size.width.value, size.height.value)
        if (rows == 0) return
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                data == null -> CircularProgressIndicator(color = fg)
                data.isEmpty() -> Text(
                    text = stringResource(MR.strings.information_no_recent),
                    style = TextStyle(color = fg, fontSize = 12.sp),
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    data.take(rows * columns).chunked(columns).forEach { row ->
                        Row(modifier = GlanceModifier.padding(top = 6.dp)) {
                            row.forEach { Cover(it, fg) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Cover(item: NewChapters, fg: ColorProvider) {
        val intent = Intent(LocalContext.current, Class.forName(Constants.MAIN_ACTIVITY)).apply {
            action = Constants.SHORTCUT_MANGA
            putExtra(Constants.MANGA_EXTRA, item.mangaId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addCategory(item.mangaId.toString())
        }
        Box(
            modifier = GlanceModifier.padding(horizontal = 3.dp).clickable(actionStartActivity(intent)),
            contentAlignment = Alignment.TopEnd,
        ) {
            UpdatesMangaCover(cover = item.cover)
            Box(
                modifier = GlanceModifier
                    .padding(4.dp),
            ) {
                Text(
                    text = item.count.toString(),
                    style = TextStyle(color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier
                        .background(ImageProvider(R.drawable.appwidget_badge_background))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }

    private fun refreshIntent(context: Context, categoryId: Long?): Intent {
        return Intent().apply {
            component = ComponentName(context.packageName, Constants.LIBRARY_REFRESH_RECEIVER)
            categoryId?.let { putExtra(Constants.LIBRARY_REFRESH_CATEGORY, it) }
            // Um PendingIntent por botão (sem isto os botões partilhavam o mesmo extra)
            addCategory("refresh:${categoryId ?: "all"}")
        }
    }

    private fun ReadingStatus.label() = when (this) {
        ReadingStatus.READING -> MR.strings.reading_status_reading
        ReadingStatus.COMPLETED -> MR.strings.reading_status_completed
        ReadingStatus.DROPPED -> MR.strings.reading_status_dropped
        ReadingStatus.PLAN_TO_READ -> MR.strings.reading_status_plan_to_read
    }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun List<UpdatesWithRelations>.prepareData(context: Context, limit: Int): List<NewChapters> {
        val widthPx = CoverWidth.value.toInt().dpToPx
        val heightPx = CoverHeight.value.toInt().dpToPx
        val roundPx = context.resources.getDimension(R.dimen.appwidget_inner_radius)
        return withIOContext {
            groupBy { it.mangaId }.values
                .take(limit)
                .map { updates ->
                    val first = updates.first()
                    val request = ImageRequest.Builder(context)
                        .data(first.coverData)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .precision(Precision.EXACT)
                        .size(widthPx, heightPx)
                        .scale(Scale.FILL)
                        .let {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                it.transformations(RoundedCornersTransformation(roundPx))
                            } else {
                                it
                            }
                        }
                        .build()
                    val bitmap = context.imageLoader.executeBlocking(request)
                        .image
                        ?.asDrawable(context.resources)
                        ?.toBitmap()
                    NewChapters(first.mangaId, updates.size, bitmap)
                }
        }
    }

    private data class NewChapters(val mangaId: Long, val count: Int, val cover: Bitmap?)

    private companion object {
        // Cabeçalho, botões e margens; cada capa ocupa 64 × 93 dp
        const val HEADER_HEIGHT = 102f

        fun coverGrid(width: Float, height: Float): Pair<Int, Int> {
            val rows = ((height - HEADER_HEIGHT) / 93f).toInt().coerceIn(0, 10)
            val columns = ((width - 20f) / 64f).toInt().coerceIn(1, 10)
            return rows to columns
        }
    }
}
