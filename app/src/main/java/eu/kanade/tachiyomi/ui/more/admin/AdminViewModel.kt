package eu.kanade.tachiyomi.ui.more.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.account.AdminRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AdminViewModel(
    private val adminRepository: AdminRepository,
) : ViewModel() {

    /** Lê os dados da cloud e devolve (id da conta, JSON) ou null se não houver sessão ou rede. */
    fun loadCloud(onResult: (userId: String, json: String) -> Unit, onFailure: () -> Unit) {
        viewModelScope.launchIO {
            val userId = adminRepository.currentUserId() ?: return@launchIO onFailure()
            try {
                onResult(userId, adminRepository.loadData(userId).toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Admin: falhou a leitura da cloud" }
                onFailure()
            }
        }
    }

    /** Corre fora do ViewModel: sair do ecrã logo a seguir a uma alteração não a perde. */
    fun save(key: String, json: String, onSaved: () -> Unit, onFailure: () -> Unit) {
        adminRepository.scope.launch {
            val userId = adminRepository.currentUserId() ?: return@launch onFailure()
            try {
                val value: JsonElement = Json.parseToJsonElement(json)
                adminRepository.save(userId, key, value)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Admin: falhou o envio de $key" }
                onFailure()
            }
        }
    }
}
