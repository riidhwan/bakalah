package eu.kanade.tachiyomi.ui.manga

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.manga.LocalVaultImportScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.vault.LocalVaultImportPreviewResult
import eu.kanade.tachiyomi.data.vault.LocalVaultImportResult
import eu.kanade.tachiyomi.data.vault.LocalVaultImportService
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.LocalVaultImportDuplicateState
import tachiyomi.domain.vault.model.LocalVaultImportPlan
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalVaultImportScreen(
    private val mangaId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val screenModel = rememberScreenModel { LocalVaultImportScreenModel(mangaId) }
        val state by screenModel.state.collectAsState()

        BackHandler(enabled = state.isImporting) {}

        LocalVaultImportScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            navigateUp = { if (!state.isImporting) navigator.pop() },
            onOpenSettings = { navigator.push(SettingsScreen(SettingsScreen.Destination.DataAndStorage)) },
            onTargetSelected = screenModel::selectTarget,
            onChapterSelected = screenModel::setChapterSelected,
            onSelectAll = screenModel::selectAll,
            onSelectNone = screenModel::selectNone,
            onImport = screenModel::import,
            onRetry = screenModel::retry,
            onOpenVault = {
                state.success?.vaultMangaId
                    ?.let { navigator.push(VaultMangaScreen(it)) }
                    ?: scope.launch {
                        HomeScreen.openTab(HomeScreen.Tab.Vault)
                        navigator.popUntilRoot()
                    }
            },
            onDone = { navigator.pop() },
        )
    }
}

class LocalVaultImportScreenModel(
    private val mangaId: Long,
    private val getManga: GetManga = Injekt.get(),
    private val importService: LocalVaultImportService = Injekt.get(),
    private val repository: VaultRepository = Injekt.get(),
    private val preferences: ContentVaultPreferences = Injekt.get(),
) : StateScreenModel<LocalVaultImportScreenModel.State>(State()) {

    private var manga: Manga? = null

    init {
        screenModelScope.launchIO {
            val loadedManga = getManga.await(mangaId)
            if (loadedManga == null) {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = ImportError.LOAD_FAILED,
                    )
                }
                return@launchIO
            }
            manga = loadedManga
            loadPreview()
        }
    }

    fun retry() {
        val currentTarget = state.value.selectedTarget
        screenModelScope.launchIO {
            val currentManga = manga ?: getManga.await(mangaId)
            if (currentManga == null) {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = ImportError.LOAD_FAILED,
                    )
                }
                return@launchIO
            }
            manga = currentManga
            loadPreview(
                manga = currentManga,
                target = currentTarget,
                resetSelection = state.value.plan == null,
            )
        }
    }

    fun selectTarget(target: TargetSelection) {
        val currentManga = manga ?: return
        screenModelScope.launchIO {
            loadPreview(
                manga = currentManga,
                target = target,
                resetSelection = true,
            )
        }
    }

    fun setChapterSelected(selectionId: String, selected: Boolean) {
        val chapter = state.value.plan
            ?.chapters
            ?.firstOrNull { it.chapter.selectionId == selectionId }
            ?: return
        if (chapter.duplicateState == LocalVaultImportDuplicateState.EXACT) return

        mutableState.update {
            val selectedIds = if (selected) {
                it.selectedChapterIds + selectionId
            } else {
                it.selectedChapterIds - selectionId
            }
            it.copy(
                selectedChapterIds = selectedIds,
                error = it.error.takeUnless { error -> error == ImportError.NOTHING_SELECTED },
            )
        }
    }

    fun selectAll() {
        mutableState.update { state ->
            state.copy(
                selectedChapterIds = state.selectableChapterIds,
                error = state.error.takeUnless { it == ImportError.NOTHING_SELECTED },
            )
        }
    }

    fun selectNone() {
        mutableState.update { it.copy(selectedChapterIds = emptySet()) }
    }

    fun import() {
        val currentManga = manga ?: return
        val currentState = state.value
        val target = currentState.selectedTarget
        if (target == null) {
            mutableState.update { it.copy(error = ImportError.TARGET_REQUIRED) }
            return
        }
        if (currentState.selectedImportableCount == 0) {
            mutableState.update { it.copy(error = ImportError.NOTHING_SELECTED) }
            return
        }

        screenModelScope.launchIO {
            mutableState.update {
                it.copy(
                    isImporting = true,
                    error = null,
                    success = null,
                )
            }
            val result = runCatching {
                importService.import(
                    localManga = currentManga,
                    selectedChapterIds = currentState.selectedChapterIds,
                    targetMangaId = (target as? TargetSelection.Existing)?.mangaId,
                    createNew = target == TargetSelection.CreateNew,
                )
            }.getOrElse {
                logcat(LogPriority.ERROR, it) { "Local-to-Vault Import failed for mangaId=$mangaId" }
                LocalVaultImportResult.UploadFailed
            }

            when (result) {
                LocalVaultImportResult.IncompleteConfiguration -> showError(ImportError.INCOMPLETE_CONFIGURATION)
                LocalVaultImportResult.LocalMangaNotFound -> showError(ImportError.LOCAL_MANGA_NOT_FOUND)
                is LocalVaultImportResult.TargetChoiceRequired -> {
                    mutableState.update {
                        it.copy(
                            isImporting = false,
                            plan = result.plan,
                            error = ImportError.TARGET_REQUIRED,
                        )
                    }
                }
                is LocalVaultImportResult.NothingSelected -> {
                    mutableState.update {
                        it.copy(
                            isImporting = false,
                            plan = result.plan,
                            error = ImportError.NOTHING_SELECTED,
                        )
                    }
                }
                is LocalVaultImportResult.ManifestUnavailable -> showError(ImportError.MANIFEST_UNAVAILABLE)
                is LocalVaultImportResult.IdentityChanged -> showError(ImportError.IDENTITY_CHANGED)
                LocalVaultImportResult.UploadFailed -> showError(ImportError.UPLOAD_FAILED)
                is LocalVaultImportResult.Imported -> {
                    mutableState.update {
                        it.copy(
                            isImporting = false,
                            error = null,
                            success = ImportSuccess(
                                mangaIdentity = result.mangaIdentity,
                                vaultMangaId = resolveImportedMangaId(result.mangaIdentity),
                                importedChapterCount = result.importedChapterCount,
                                skippedExactDuplicateCount = result.skippedExactDuplicateCount,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadPreview() {
        loadPreview(
            manga = manga ?: return,
            target = null,
            resetSelection = true,
        )
    }

    private suspend fun loadPreview(
        manga: Manga,
        target: TargetSelection?,
        resetSelection: Boolean,
    ) {
        mutableState.update {
            it.copy(
                isLoading = true,
                error = null,
                success = null,
            )
        }
        val result = runCatching {
            importService.preview(
                localManga = manga,
                targetMangaId = (target as? TargetSelection.Existing)?.mangaId,
                createNew = target == TargetSelection.CreateNew,
            )
        }.getOrElse {
            logcat(LogPriority.ERROR, it) { "Failed to preview Local-to-Vault Import for mangaId=$mangaId" }
            LocalVaultImportPreviewResult.LocalMangaNotFound
        }

        when (result) {
            LocalVaultImportPreviewResult.IncompleteConfiguration -> {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        mangaTitle = manga.title,
                        error = ImportError.INCOMPLETE_CONFIGURATION,
                    )
                }
            }
            LocalVaultImportPreviewResult.LocalMangaNotFound -> {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        mangaTitle = manga.title,
                        error = ImportError.LOCAL_MANGA_NOT_FOUND,
                    )
                }
            }
            is LocalVaultImportPreviewResult.Success -> {
                val selectedTarget = target ?: result.plan.target.toSelection()
                mutableState.update { state ->
                    state.copy(
                        isLoading = false,
                        mangaTitle = manga.title,
                        plan = result.plan,
                        availableTargets = result.availableTargets,
                        selectedTarget = selectedTarget,
                        selectedChapterIds = if (resetSelection) {
                            result.plan.defaultSelectedChapterIds
                        } else {
                            state.selectedChapterIds
                        },
                        error = null,
                    )
                }
            }
        }
    }

    private fun showError(error: ImportError) {
        mutableState.update {
            it.copy(
                isImporting = false,
                error = error,
            )
        }
    }

    private suspend fun resolveImportedMangaId(identity: VaultIdentity): Long? {
        val vaultIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() } ?: return null
        val vault = repository.getVaultByIdentity(ContentVaultIdentity(vaultIdentity)) ?: return null
        return repository.getMangaByIdentity(vault.id, identity)?.id
    }

    data class State(
        val isLoading: Boolean = true,
        val isImporting: Boolean = false,
        val mangaTitle: String = "",
        val plan: LocalVaultImportPlan? = null,
        val availableTargets: List<VaultManga> = emptyList(),
        val selectedTarget: TargetSelection? = null,
        val selectedChapterIds: Set<String> = emptySet(),
        val error: ImportError? = null,
        val success: ImportSuccess? = null,
    ) {
        val selectableChapterIds: Set<String>
            get() = plan
                ?.chapters
                .orEmpty()
                .filter { it.duplicateState != LocalVaultImportDuplicateState.EXACT }
                .map { it.chapter.selectionId }
                .toSet()

        val selectedImportableCount: Int
            get() = selectedChapterIds.intersect(selectableChapterIds).size

        val recognizedChapterCount: Int
            get() = plan?.chapters.orEmpty().size

        val selectedCbzConversionCount: Int
            get() = plan
                ?.chapters
                .orEmpty()
                .count {
                    it.chapter.selectionId in selectedChapterIds &&
                        it.chapter.requiresLocalCbzConversion &&
                        it.duplicateState != LocalVaultImportDuplicateState.EXACT
                }

        val selectedKnownSourceSizeBytes: Long
            get() = plan
                ?.chapters
                .orEmpty()
                .filter {
                    it.chapter.selectionId in selectedChapterIds &&
                        it.duplicateState != LocalVaultImportDuplicateState.EXACT &&
                        !it.chapter.requiresLocalCbzConversion
                }
                .sumOf { it.chapter.sizeBytes }

        val hasSelectedKnownSourceSize: Boolean
            get() = plan
                ?.chapters
                .orEmpty()
                .any {
                    it.chapter.selectionId in selectedChapterIds &&
                        it.duplicateState != LocalVaultImportDuplicateState.EXACT &&
                        !it.chapter.requiresLocalCbzConversion
                }
    }

    sealed interface TargetSelection {
        data object CreateNew : TargetSelection
        data class Existing(val mangaId: Long) : TargetSelection
    }

    enum class ImportError {
        INCOMPLETE_CONFIGURATION,
        LOCAL_MANGA_NOT_FOUND,
        TARGET_REQUIRED,
        NOTHING_SELECTED,
        MANIFEST_UNAVAILABLE,
        IDENTITY_CHANGED,
        UPLOAD_FAILED,
        LOAD_FAILED,
    }

    data class ImportSuccess(
        val mangaIdentity: VaultIdentity,
        val vaultMangaId: Long?,
        val importedChapterCount: Int,
        val skippedExactDuplicateCount: Int,
    )
}

private val LocalVaultImportPlan.defaultSelectedChapterIds: Set<String>
    get() = chapters
        .filter { it.selectedByDefault && it.duplicateState != LocalVaultImportDuplicateState.EXACT }
        .map { it.chapter.selectionId }
        .toSet()

private fun LocalVaultImportTarget.toSelection(): LocalVaultImportScreenModel.TargetSelection? {
    return when (this) {
        LocalVaultImportTarget.CreateNew -> LocalVaultImportScreenModel.TargetSelection.CreateNew
        is LocalVaultImportTarget.Existing -> LocalVaultImportScreenModel.TargetSelection.Existing(manga.id)
        is LocalVaultImportTarget.Choose -> null
    }
}
