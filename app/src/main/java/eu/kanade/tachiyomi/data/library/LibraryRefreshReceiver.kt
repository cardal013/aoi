package eu.kanade.tachiyomi.data.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.core.common.Constants
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR

/** Aoi: recebe os botões do widget da biblioteca e atualiza esse estado (ou tudo) como o puxar para baixo. */
class LibraryRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val category = intent.getLongExtra(Constants.LIBRARY_REFRESH_CATEGORY, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }
            ?.let { Category(id = it, name = "", order = 0, flags = 0) }
        val started = LibraryUpdateJob.startNow(context.workManager, category)
        context.toast(
            when {
                !started -> MR.strings.update_already_running
                category != null -> MR.strings.updating_category
                else -> MR.strings.updating_library
            },
        )
    }
}
