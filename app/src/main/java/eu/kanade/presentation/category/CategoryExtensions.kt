package eu.kanade.presentation.category

import android.content.Context
import androidx.compose.runtime.Composable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.ReadingStatus
import tachiyomi.domain.manga.model.categoryId
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

val Category.visualName: String
    @Composable
    get() = when {
        isSystemCategory -> stringResource(MR.strings.label_default)
        id == ReadingStatus.READING.categoryId -> stringResource(MR.strings.reading_status_reading)
        id == ReadingStatus.PLAN_TO_READ.categoryId -> stringResource(MR.strings.reading_status_plan_to_read)
        id == ReadingStatus.COMPLETED.categoryId -> stringResource(MR.strings.reading_status_completed)
        id == ReadingStatus.DROPPED.categoryId -> stringResource(MR.strings.reading_status_dropped)
        else -> name
    }

fun Category.visualName(context: Context): String =
    when {
        isSystemCategory -> context.stringResource(MR.strings.label_default)
        id == ReadingStatus.READING.categoryId -> context.stringResource(MR.strings.reading_status_reading)
        id == ReadingStatus.PLAN_TO_READ.categoryId -> context.stringResource(MR.strings.reading_status_plan_to_read)
        id == ReadingStatus.COMPLETED.categoryId -> context.stringResource(MR.strings.reading_status_completed)
        id == ReadingStatus.DROPPED.categoryId -> context.stringResource(MR.strings.reading_status_dropped)
        else -> name
    }
