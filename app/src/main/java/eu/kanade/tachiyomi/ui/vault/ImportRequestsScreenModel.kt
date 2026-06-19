package eu.kanade.tachiyomi.ui.vault

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.vault.model.VaultImportRequestSummary
import tachiyomi.domain.vault.repository.VaultRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ImportRequestsScreenModel(
    private val repository: VaultRepository = Injekt.get(),
) : StateScreenModel<ImportRequestsScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            repository.getImportRequestSummariesAsFlow()
                .catch {
                    logcat(LogPriority.ERROR, it)
                    mutableState.update { state -> state.copy(isLoading = false, isError = true) }
                }
                .collectLatest { requests ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isError = false,
                            requests = requests,
                        )
                    }
                }
        }
    }

    data class State(
        val isLoading: Boolean = true,
        val isError: Boolean = false,
        val requests: List<VaultImportRequestSummary> = emptyList(),
    )
}
