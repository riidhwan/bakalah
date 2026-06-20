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
import tachiyomi.domain.vault.service.ContentVaultPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ImportRequestsScreenModel(
    private val repository: VaultRepository = Injekt.get(),
    private val preferences: ContentVaultPreferences = Injekt.get(),
) : StateScreenModel<ImportRequestsScreenModel.State>(
    State(includeSensitiveContent = preferences.includeSensitiveContent.get()),
) {

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

        screenModelScope.launchIO {
            preferences.includeSensitiveContent.changes()
                .collectLatest { includeSensitive ->
                    mutableState.update { it.copy(includeSensitiveContent = includeSensitive) }
                }
        }
    }

    fun setIncludeSensitiveContent(include: Boolean) {
        preferences.includeSensitiveContent.set(include)
        mutableState.update { it.copy(includeSensitiveContent = include) }
    }

    data class State(
        val isLoading: Boolean = true,
        val isError: Boolean = false,
        val requests: List<VaultImportRequestSummary> = emptyList(),
        val includeSensitiveContent: Boolean = false,
    ) {
        val visibleRequests: List<VaultImportRequestSummary>
            get() = if (includeSensitiveContent) {
                requests
            } else {
                requests.filterNot { it.isTargetSensitive }
            }
    }
}
