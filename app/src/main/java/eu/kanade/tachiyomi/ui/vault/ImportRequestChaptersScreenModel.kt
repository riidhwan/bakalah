package eu.kanade.tachiyomi.ui.vault

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.repository.VaultRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ImportRequestChaptersScreenModel(
    requestId: Long,
    private val repository: VaultRepository = Injekt.get(),
) : StateScreenModel<ImportRequestChaptersScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            repository.getImportRequestAsFlow(requestId)
                .catch {
                    logcat(LogPriority.ERROR, it)
                    mutableState.update { state -> state.copy(isLoading = false, isError = true) }
                }
                .collectLatest { request ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isError = false,
                            request = request,
                        )
                    }
                }
        }
    }

    data class State(
        val isLoading: Boolean = true,
        val isError: Boolean = false,
        val request: VaultImportRequest? = null,
    )
}
