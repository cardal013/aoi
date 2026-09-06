package eu.kanade.tachiyomi.ui.setting

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.domain.extension.interactor.AddExtensionStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SettingsViewModel(
    private val sourcePreferences: SourcePreferences,
    private val downloadPreferences: DownloadPreferences,
    private val addExtensionStore: AddExtensionStore,
    private val extensionManager: ExtensionManager,
) : ViewModel() {

    val state: StateFlow<State> = combine(
        downloadPreferences.wifiDownloadPromptShown.changes(),
        sourcePreferences.extensionRepoImportPromptShown.changes(),
    ) { wifiPromptShown, extensionPromptShown ->
        State(
            showWifiPrompt = !wifiPromptShown,
            showExtensionPrompt = wifiPromptShown && !extensionPromptShown,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun onDismissWifiPrompt(onlyOnWifi: Boolean) {
        downloadPreferences.downloadOnlyOverWifi.set(onlyOnWifi)
        downloadPreferences.wifiDownloadPromptShown.set(true)
    }

    fun onDismissExtensionPrompt() {
        sourcePreferences.extensionRepoImportPromptShown.set(true)
    }

    fun importDefaultRepositories() {
        onDismissExtensionPrompt()
        viewModelScope.launchIO {
            val repos = listOf(
                "https://raw.githubusercontent.com/yuzono/manga-repo/repo/repo.json",
                "https://raw.githubusercontent.com/Kareadita/tach-extension/repo/repo.json",
                "https://raw.githubusercontent.com/Suwayomi/tachiyomi-extension/repo/repo.json",
                "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
                "https://raw.githubusercontent.com/yuzono/cursed-manga-repo/repo/index.pb",
            )
            repos.forEach { url ->
                logcat { "AOI_IMPORT: Starting import for $url" }
                addExtensionStore(url)
                    .onSuccess { logcat { "AOI_IMPORT: Success for $url" } }
                    .onFailure { logcat(LogPriority.ERROR, it) { "AOI_IMPORT: Failed for $url" } }
            }
            extensionManager.findAvailableExtensions()
        }
    }

    @Immutable
    data class State(
        val showWifiPrompt: Boolean = false,
        val showExtensionPrompt: Boolean = false,
    )
}
