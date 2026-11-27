package mihon.presentation.widget.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import tachiyomi.presentation.widget.BaseUpdatesGridGlanceWidget
import tachiyomi.presentation.widget.LibraryRefreshGlanceWidget

@ContributesTo(AppScope::class)
interface PresentationWidgetGraph {
    fun inject(widget: BaseUpdatesGridGlanceWidget)

    fun inject(widget: LibraryRefreshGlanceWidget)
}
