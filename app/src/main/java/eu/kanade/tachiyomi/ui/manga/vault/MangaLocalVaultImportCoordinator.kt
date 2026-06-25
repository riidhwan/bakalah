package eu.kanade.tachiyomi.ui.manga.vault

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultCaptureJob
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultImportJob
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.manga.dialog.MangaVaultDialog
import eu.kanade.tachiyomi.ui.manga.effect.MangaUiEffect
import eu.kanade.tachiyomi.ui.manga.model.ChapterList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem

internal class MangaLocalVaultImportCoordinator(
    private val runtime: Runtime,
    private val services: Services,
    private val callbacks: Callbacks,
) {
    data class Runtime(
        val context: Context,
        val lifecycle: Lifecycle,
        val screenModelScope: CoroutineScope,
    )

    data class Services(
        val localSourceFileSystem: LocalSourceFileSystem,
        val vaultRepository: VaultRepository,
        val contentVaultPreferences: ContentVaultPreferences,
        val localVaultImportStateBuilder: LocalVaultImportScreenStateBuilder,
    )

    data class Callbacks(
        val getState: () -> MangaScreenModel.State.Success?,
        val setLocalVaultImportState: (LocalVaultImportState) -> Unit,
        val showDialog: (MangaVaultDialog) -> Unit,
        val dismissDialog: () -> Unit,
        val selectedChapters: (List<ChapterList.Item>) -> List<Chapter>,
        val clearSelection: () -> Unit,
        val showUiEffect: (MangaUiEffect) -> Unit,
    )

    private var localVaultImportJob: Job? = null

    fun restartObservation(manga: Manga, source: Source) {
        localVaultImportJob?.cancel()
        fun observe(request: LocalVaultObservationRequest): Job {
            return observeLocalVaultImport(runtime, services, callbacks, request) { inputs ->
                refreshState(
                    LocalVaultImportRefreshRequest(
                        localManga = request.manga,
                        workflow = request.workflow,
                        vaultManga = inputs.vaultManga,
                        vaultChapters = inputs.vaultChapters,
                        hint = inputs.hint,
                    ),
                )
            }
        }

        localVaultImportJob = when {
            source is LocalSource && services.localSourceFileSystem.getMangaDirectory(manga.url) != null -> {
                observe(LocalVaultObservationRequest(manga, LocalVaultWorkflow.LocalImport))
            }
            manga.favorite && source !is LocalSource -> {
                observe(LocalVaultObservationRequest(manga, LocalVaultWorkflow.LibraryCapture))
            }
            else -> null
        }
    }

    fun showTargetSetup(pendingAddToVault: Boolean) {
        val state = callbacks.getState() ?: return
        val localVaultImport = state.localVaultImport ?: return
        val selectedTarget = localVaultImport.pendingTarget ?: localVaultImport.linkedTargetSelection()
        callbacks.showDialog(
            MangaVaultDialog.LocalVaultTargetSetup(
                initialTitle = when (selectedTarget) {
                    is LocalVaultImportTargetSelection.CreateNew -> selectedTarget.title
                    is LocalVaultImportTargetSelection.Existing ->
                        localVaultImport.availableTargets
                            .firstOrNull { target -> target.id == selectedTarget.mangaId }
                            ?.metadata
                            ?.title
                            ?: state.manga.title
                    null -> state.manga.title
                },
                targets = localVaultImport.availableTargets,
                selectedTarget = selectedTarget,
                allowCreateNew = pendingAddToVault,
                allowUnlink = !pendingAddToVault,
                pendingAddToVault = pendingAddToVault,
            ),
        )
    }

    fun selectTarget(
        target: LocalVaultImportTargetSelection?,
        pendingAddToVault: Boolean,
    ) {
        val state = callbacks.getState() ?: return
        val localVaultImport = state.localVaultImport ?: return
        when {
            pendingAddToVault -> selectPendingAddTarget(state, localVaultImport, target)
            target == null -> unlinkTarget(state, localVaultImport)
            target is LocalVaultImportTargetSelection.Existing -> linkExistingTarget(state, localVaultImport, target)
            target is LocalVaultImportTargetSelection.CreateNew -> callbacks.dismissDialog()
        }
    }

    fun startAddToVault(onOpenSettings: () -> Unit) {
        val state = callbacks.getState()
        val localVaultImport = state?.localVaultImport
        if (state != null && localVaultImport != null && callbacks.selectedChapters(state.chapters).isNotEmpty()) {
            when (localVaultImport.targetState) {
                LocalVaultImportTargetState.SetupContentVault -> onOpenSettings()
                LocalVaultImportTargetState.Stale,
                LocalVaultImportTargetState.Unlinked,
                -> showTargetSetup(pendingAddToVault = true)
                is LocalVaultImportTargetState.Linked -> startAddToVaultInternal()
            }
        }
    }

    fun confirmReplacement() {
        callbacks.dismissDialog()
        startAddToVaultInternal(replaceConfirmed = true)
    }

    private fun selectPendingAddTarget(
        state: MangaScreenModel.State.Success,
        localVaultImport: LocalVaultImportState,
        target: LocalVaultImportTargetSelection?,
    ) {
        if (target == null) {
            callbacks.dismissDialog()
            return
        }
        runtime.screenModelScope.launchIO {
            callbacks.setLocalVaultImportState(localVaultImport.copy(pendingTarget = target))
            refreshState(
                LocalVaultImportRefreshRequest(
                    localManga = state.manga,
                    workflow = localVaultImport.workflow,
                    vaultManga = localVaultImport.availableTargets,
                    vaultChapters = localVaultImport.loadVaultChapters(services.vaultRepository),
                    hint = null,
                    pendingTargetOverride = target,
                ),
            )
            withUIContext {
                callbacks.dismissDialog()
                startAddToVaultInternal()
            }
        }
    }

    private fun unlinkTarget(
        state: MangaScreenModel.State.Success,
        localVaultImport: LocalVaultImportState,
    ) {
        runtime.screenModelScope.launchIO {
            services.vaultRepository.deleteImportTargetHint(state.manga.id)
            callbacks.setLocalVaultImportState(
                localVaultImport.copy(
                    pendingTarget = null,
                    targetState = LocalVaultImportTargetState.Unlinked,
                    duplicateChapterSelectionIds = emptySet(),
                ),
            )
        }
        callbacks.dismissDialog()
    }

    private fun linkExistingTarget(
        state: MangaScreenModel.State.Success,
        localVaultImport: LocalVaultImportState,
        target: LocalVaultImportTargetSelection.Existing,
    ) {
        runtime.screenModelScope.launchIO {
            val hint = ImportTargetHint(
                localMangaId = state.manga.id,
                localMangaIdentity = state.manga.url,
                contentVaultIdentity = services.contentVaultPreferences.configuredVaultIdentity.get()
                    .takeIf { it.isNotBlank() }
                    ?.let(::ContentVaultIdentity),
                sourceIdentity = localVaultImportSourceIdentity(
                    workflow = localVaultImport.workflow,
                    sourceId = state.manga.source,
                    mangaUrl = state.manga.url,
                ),
                vaultMangaIdentity = localVaultImport.availableTargets
                    .firstOrNull { it.id == target.mangaId }
                    ?.identity,
                vaultMangaId = target.mangaId,
                updatedAt = System.currentTimeMillis(),
            )
            services.vaultRepository.upsertImportTargetHint(hint)
            refreshState(
                LocalVaultImportRefreshRequest(
                    localManga = state.manga,
                    workflow = localVaultImport.workflow,
                    vaultManga = localVaultImport.availableTargets,
                    vaultChapters = localVaultImport.loadVaultChapters(services.vaultRepository),
                    hint = hint,
                ),
            )
        }
        callbacks.dismissDialog()
    }

    private suspend fun refreshState(request: LocalVaultImportRefreshRequest) {
        val localVaultImport = services.localVaultImportStateBuilder.build(
            workflow = request.workflow,
            expectedSourceIdentity = localVaultImportSourceIdentity(
                workflow = request.workflow,
                sourceId = request.localManga.source,
                mangaUrl = request.localManga.url,
            ),
            vaultManga = request.vaultManga,
            vaultChapters = request.vaultChapters,
            hint = request.hint,
            pendingTargetOverride = request.pendingTargetOverride
                ?: callbacks.getState()?.localVaultImport?.pendingTarget,
            chapters = callbacks.getState()?.chapters.orEmpty().map { it.chapter },
            localChapterDuplicateKeys = if (request.workflow == LocalVaultWorkflow.LocalImport) {
                localVaultImportDuplicateKeys(
                    localManga = request.localManga,
                    localSourceFileSystem = services.localSourceFileSystem,
                    chapters = callbacks.getState()?.chapters.orEmpty(),
                )
            } else {
                emptyMap()
            },
            isImportRunning = LocalVaultImportJob.isRunning(runtime.context) ||
                LibraryVaultCaptureJob.isRunning(runtime.context),
        )
        callbacks.setLocalVaultImportState(localVaultImport)
    }

    private fun startAddToVaultInternal(replaceConfirmed: Boolean = false) {
        val state = callbacks.getState()
        val localVaultImport = state?.localVaultImport
        val selectedChapters = state?.chapters?.let(callbacks.selectedChapters).orEmpty()
        val duplicateTitles = localVaultImport?.duplicateChapterTitles(selectedChapters).orEmpty()
        when {
            state == null || localVaultImport == null || selectedChapters.isEmpty() -> Unit
            duplicateTitles.isNotEmpty() && !replaceConfirmed -> callbacks.showDialog(
                MangaVaultDialog.LocalVaultReplaceChapters(
                    chapterTitles = duplicateTitles,
                ),
            )
            else -> startAddToVaultJob(
                runtime = runtime,
                callbacks = callbacks,
                request = LocalVaultImportJobRequest(
                    mangaId = state.manga.id,
                    localVaultImport = localVaultImport,
                    startRequest = localVaultImport.startRequest(
                        selectedChapters = selectedChapters,
                        replaceConfirmed = replaceConfirmed,
                    ),
                ),
            )
        }
    }
}

private data class LocalVaultObservationRequest(
    val manga: Manga,
    val workflow: LocalVaultWorkflow,
)

private data class LocalVaultImportStartRequest(
    val chapters: List<VaultImportRequestChapter>,
    val targetMangaId: Long?,
    val createNewTitle: String?,
)

private data class LocalVaultImportRefreshRequest(
    val localManga: Manga,
    val workflow: LocalVaultWorkflow,
    val vaultManga: List<VaultManga>,
    val vaultChapters: List<VaultChapter>,
    val hint: ImportTargetHint?,
    val pendingTargetOverride: LocalVaultImportTargetSelection? = null,
)

private data class LocalVaultImportJobRequest(
    val mangaId: Long,
    val localVaultImport: LocalVaultImportState,
    val startRequest: LocalVaultImportStartRequest,
)

private fun observeLocalVaultImport(
    runtime: MangaLocalVaultImportCoordinator.Runtime,
    services: MangaLocalVaultImportCoordinator.Services,
    callbacks: MangaLocalVaultImportCoordinator.Callbacks,
    request: LocalVaultObservationRequest,
    onInputs: suspend (LocalVaultImportInputs) -> Unit,
): Job {
    return runtime.screenModelScope.launchIO {
        val configuredIdentity = services.contentVaultPreferences.configuredVaultIdentity.get()
        if (configuredIdentity.isBlank()) {
            callbacks.setLocalVaultImportState(
                LocalVaultImportState(
                    targetState = LocalVaultImportTargetState.SetupContentVault,
                    workflow = request.workflow,
                ),
            )
            return@launchIO
        }

        val vault = services.vaultRepository.getVaultByIdentity(ContentVaultIdentity(configuredIdentity))
        if (vault == null) {
            callbacks.setLocalVaultImportState(
                LocalVaultImportState(
                    targetState = LocalVaultImportTargetState.SetupContentVault,
                    workflow = request.workflow,
                ),
            )
            return@launchIO
        }

        combine(
            services.vaultRepository.getMangaAsFlow(vault.id),
            services.vaultRepository.getChaptersForVaultAsFlow(vault.id),
            services.vaultRepository.getImportTargetHintAsFlow(request.manga.id),
        ) { vaultManga, vaultChapters, hint ->
            LocalVaultImportInputs(vaultManga, vaultChapters, hint)
        }
            .flowWithLifecycle(runtime.lifecycle)
            .collectLatest(onInputs)
    }
}

private fun localVaultImportDuplicateKeys(
    localManga: Manga,
    localSourceFileSystem: LocalSourceFileSystem,
    chapters: List<ChapterList.Item>,
): Map<String, String> {
    val filesByName = localSourceFileSystem.getFilesInMangaDirectory(localManga.url)
        .associateBy { it.name.orEmpty() }
    return chapters
        .mapNotNull { item ->
            val fileName = item.chapter.url.substringAfter('/', missingDelimiterValue = item.chapter.url)
            val file = filesByName[fileName] ?: return@mapNotNull null
            if (!file.isDirectory && !file.name.orEmpty().endsWith(".cbz", ignoreCase = true)) {
                return@mapNotNull null
            }
            item.chapter.url to localVaultImportDuplicateFileKey(file.name.orEmpty())
        }
        .filter { (_, duplicateKey) -> duplicateKey.isNotBlank() }
        .toMap()
}

private fun LocalVaultImportState.linkedTargetSelection(): LocalVaultImportTargetSelection? {
    return (targetState as? LocalVaultImportTargetState.Linked)
        ?.let { LocalVaultImportTargetSelection.Existing(it.mangaId) }
}

private suspend fun LocalVaultImportState.loadVaultChapters(
    vaultRepository: VaultRepository,
): List<VaultChapter> {
    val vaultId = availableTargets.firstOrNull()?.vaultId ?: return emptyList()
    return vaultRepository.getChaptersForVault(vaultId)
}

private fun LocalVaultImportState.duplicateChapterTitles(
    selectedChapters: List<Chapter>,
): List<String> {
    return selectedChapters
        .filter { it.url in duplicateChapterSelectionIds }
        .map { it.name }
}

private fun LocalVaultImportState.startRequest(
    selectedChapters: List<Chapter>,
    replaceConfirmed: Boolean,
): LocalVaultImportStartRequest {
    val target = pendingTarget ?: linkedTargetSelection()
    val replacementChapterIds = if (replaceConfirmed) {
        selectedChapters
            .filter { it.url in duplicateChapterSelectionIds }
            .map { it.url }
            .toSet()
    } else {
        emptySet()
    }
    return LocalVaultImportStartRequest(
        chapters = selectedChapters.mapIndexed { index, chapter ->
            VaultImportRequestChapter(
                chapterId = chapter.id,
                selectionId = chapter.url,
                sortOrder = index.toLong(),
                allowReplacement = chapter.url in replacementChapterIds,
            )
        },
        targetMangaId = (target as? LocalVaultImportTargetSelection.Existing)?.mangaId,
        createNewTitle = (target as? LocalVaultImportTargetSelection.CreateNew)?.title,
    )
}

private suspend fun startLocalVaultImportJob(
    context: Context,
    workflow: LocalVaultWorkflow,
    mangaId: Long,
    request: LocalVaultImportStartRequest,
): Boolean {
    return when (workflow) {
        LocalVaultWorkflow.LocalImport -> LocalVaultImportJob.startNow(
            context = context.applicationContext,
            mangaId = mangaId,
            selectedChapters = request.chapters,
            targetMangaId = request.targetMangaId,
            createNewTitle = request.createNewTitle,
        )
        LocalVaultWorkflow.LibraryCapture -> LibraryVaultCaptureJob.startNow(
            context = context.applicationContext,
            mangaId = mangaId,
            selectedChapters = request.chapters,
            targetMangaId = request.targetMangaId,
            createNewTitle = request.createNewTitle,
        )
    }
}

private fun startAddToVaultJob(
    runtime: MangaLocalVaultImportCoordinator.Runtime,
    callbacks: MangaLocalVaultImportCoordinator.Callbacks,
    request: LocalVaultImportJobRequest,
) {
    runtime.screenModelScope.launch {
        val localVaultImport = request.localVaultImport
        val started = startLocalVaultImportJob(
            context = runtime.context,
            workflow = localVaultImport.workflow,
            mangaId = request.mangaId,
            request = request.startRequest,
        )
        if (started) {
            callbacks.clearSelection()
            callbacks.setLocalVaultImportState(
                localVaultImport.copy(
                    pendingTarget = null,
                    isImportRunning = true,
                ),
            )
        }
        callbacks.showUiEffect(
            MangaUiEffect.ShowSnackbar(
                message = runtime.context.stringResource(
                    if (started) {
                        MR.strings.vault_import_ongoing
                    } else {
                        MR.strings.vault_import_error_already_running
                    },
                ),
            ),
        )
    }
}
