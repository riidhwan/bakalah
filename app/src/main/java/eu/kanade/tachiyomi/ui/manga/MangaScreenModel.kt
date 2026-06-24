package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.local.LocalMangaDeletionResult
import eu.kanade.tachiyomi.data.local.LocalMangaDeletionService
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetSameTitleLibraryManga
import tachiyomi.domain.manga.interactor.ManageLibraryMangaGroup
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.cancellation.CancellationException

class MangaScreenModel(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val mangaId: Long,
    private val isFromSource: Boolean,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    readerPreferences: ReaderPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val getMangaAndChapters: GetMangaWithChapters = Injekt.get(),
    private val getSameTitleLibraryManga: GetSameTitleLibraryManga = Injekt.get(),
    private val getAvailableScanlators: GetAvailableScanlators = Injekt.get(),
    private val getExcludedScanlators: GetExcludedScanlators = Injekt.get(),
    private val setExcludedScanlators: SetExcludedScanlators = Injekt.get(),
    private val setMangaChapterFlags: SetMangaChapterFlags = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val manageLibraryMangaGroup: ManageLibraryMangaGroup = Injekt.get(),
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get(),
    private val localSourceFileSystem: LocalSourceFileSystem = Injekt.get(),
    private val localMangaDeletionService: LocalMangaDeletionService = Injekt.get(),
    private val vaultRepository: VaultRepository = Injekt.get(),
    private val contentVaultPreferences: ContentVaultPreferences = Injekt.get(),
    private val localVaultImportStateBuilder: LocalVaultImportScreenStateBuilder = LocalVaultImportScreenStateBuilder(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val libraryMangaGroupStateBuilder: LibraryMangaGroupStateBuilder = LibraryMangaGroupStateBuilder(
        sourceName = { sourceId -> sourceManager.getOrStub(sourceId).getNameForMangaInfo() },
    ),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<MangaScreenModel.State>(State.Loading) {

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    fun showSnackbar(message: String) {
        screenModelScope.launch {
            snackbarHostState.showSnackbar(message = message)
        }
    }

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(screenModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val chapterSelection = MangaChapterSelectionState()
    private val activeMangaId = MutableStateFlow(mangaId)
    private val refreshedOnLoadMangaIds = mutableSetOf<Long>()
    private var loadedMangaId: Long? = null
    private var trackerJob: Job? = null

    @Volatile
    private var isDeletingLocalManga = false

    private val localVaultImportCoordinator = MangaLocalVaultImportCoordinator(
        runtime = MangaLocalVaultImportCoordinator.Runtime(
            context = context,
            lifecycle = lifecycle,
            screenModelScope = screenModelScope,
            snackbarHostState = snackbarHostState,
        ),
        services = MangaLocalVaultImportCoordinator.Services(
            localSourceFileSystem = localSourceFileSystem,
            vaultRepository = vaultRepository,
            contentVaultPreferences = contentVaultPreferences,
            localVaultImportStateBuilder = localVaultImportStateBuilder,
        ),
        callbacks = MangaLocalVaultImportCoordinator.Callbacks(
            getState = { successState },
            setLocalVaultImportState = ::updateLocalVaultImportState,
            showDialog = { dialog -> updateSuccessState { it.copy(dialog = dialog) } },
            dismissDialog = ::dismissDialog,
            selectedChapters = chapterSelection::selectedChapters,
            clearSelection = { toggleAllSelection(false) },
        ),
    )

    private val libraryActionCoordinator = MangaLibraryActionCoordinator(
        MangaLibraryActionCoordinator.Dependencies(
            libraryPreferences = libraryPreferences,
            getSameTitleLibraryManga = getSameTitleLibraryManga,
            getCategories = getCategories,
            updateManga = updateManga,
            setMangaCategories = setMangaCategories,
            manageLibraryMangaGroup = manageLibraryMangaGroup,
            libraryMangaGroupStateBuilder = libraryMangaGroupStateBuilder,
        ),
    )

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    init {
        screenModelScope.launchIO {
            activeMangaId
                .flatMapLatest { selectedMangaId ->
                    combine(
                        getMangaAndChapters.subscribe(selectedMangaId, applyScanlatorFilter = true)
                            .distinctUntilChanged(),
                        downloadCache.changes,
                        downloadManager.queueState,
                    ) { mangaAndChapters, _, _ -> mangaAndChapters }
                }
                .catch { error ->
                    if (!isDeletingLocalManga) throw error
                }
                .flowWithLifecycle(lifecycle)
                .collectLatest { (manga, chapters) ->
                    val isMangaSwitch = loadedMangaId != manga.id
                    if (isMangaSwitch) {
                        chapterSelection.clear()
                    }

                    if (!manga.favorite) {
                        setMangaDefaultChapterFlags.await(manga)
                    }

                    val chapterItems = chapters.toChapterListItems(manga)
                    val needRefreshInfo = !manga.initialized
                    val needRefreshChapter = chapterItems.isEmpty()
                    val source = sourceManager.getOrStub(manga.source)
                    val libraryMangaGroupTabs = getLibraryMangaGroupTabs(manga.id)

                    mutableState.update { previousState ->
                        val previousSuccessState = previousState as? State.Success
                        State.Success(
                            manga = manga,
                            source = source,
                            isFromSource = isFromSource,
                            chapters = chapterItems,
                            availableScanlators = previousSuccessState
                                ?.takeUnless { isMangaSwitch }
                                ?.availableScanlators
                                ?: emptySet(),
                            excludedScanlators = previousSuccessState
                                ?.takeUnless { isMangaSwitch }
                                ?.excludedScanlators
                                ?: emptySet(),
                            isRefreshingData = needRefreshInfo || needRefreshChapter,
                            dialog = previousSuccessState?.dialog,
                            hasPromptedToAddBefore = previousSuccessState?.hasPromptedToAddBefore ?: false,
                            trackingCount = if (isMangaSwitch) 0 else previousSuccessState?.trackingCount ?: 0,
                            hasLoggedInTrackers = if (isMangaSwitch) {
                                false
                            } else {
                                previousSuccessState?.hasLoggedInTrackers ?: false
                            },
                            hideMissingChapters = libraryPreferences.hideMissingChapters.get(),
                            canEditLocalMetadata = source is LocalSource &&
                                localSourceFileSystem.getMangaDirectory(manga.url) != null,
                            localVaultImport = if (isMangaSwitch) null else previousSuccessState?.localVaultImport,
                            libraryMangaGroupTabs = libraryMangaGroupTabs,
                        )
                    }

                    if (isMangaSwitch) {
                        loadedMangaId = manga.id
                        localVaultImportCoordinator.restartObservation(manga, source)
                        observeTrackers(manga.id, source)
                    }

                    if (
                        manga.id !in refreshedOnLoadMangaIds &&
                        screenModelScope.isActive &&
                        (needRefreshInfo || needRefreshChapter)
                    ) {
                        refreshedOnLoadMangaIds.add(manga.id)
                        try {
                            val fetchFromSourceTasks = listOf(
                                async { if (needRefreshInfo) fetchMangaFromSource() },
                                async { if (needRefreshChapter) fetchChaptersFromSource() },
                            )
                            fetchFromSourceTasks.awaitAll()
                        } finally {
                            updateSuccessState { it.copy(isRefreshingData = false) }
                        }
                    }
                }
        }

        screenModelScope.launchIO {
            activeMangaId
                .flatMapLatest { getExcludedScanlators.subscribe(it) }
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { excludedScanlators ->
                    updateSuccessState {
                        it.copy(excludedScanlators = excludedScanlators)
                    }
                }
        }

        screenModelScope.launchIO {
            activeMangaId
                .flatMapLatest { getAvailableScanlators.subscribe(it) }
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { availableScanlators ->
                    updateSuccessState {
                        it.copy(availableScanlators = availableScanlators)
                    }
                }
        }

        observeDownloads()
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        screenModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            try {
                val fetchFromSourceTasks = listOf(
                    async { fetchMangaFromSource(manualFetch) },
                    async { fetchChaptersFromSource(manualFetch) },
                )
                fetchFromSourceTasks.awaitAll()
            } finally {
                updateSuccessState { it.copy(isRefreshingData = false) }
            }
        }
    }

    // Manga info - start

    /**
     * Fetch manga information from source.
     */
    private suspend fun fetchMangaFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val networkManga = state.source.getMangaDetails(state.manga.toSManga())
                updateManga.awaitUpdateFromSource(state.manga, networkManga, manualFetch)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e

            // Ignore early hints "errors" that aren't handled by OkHttp
            if (e is HttpException && e.code == 103) return

            logcat(LogPriority.ERROR, e)
            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = with(context) { e.formattedMessage })
            }
        }
    }

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                screenModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        screenModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                if (libraryActionCoordinator.removeFromLibrary(manga)) {
                    withUIContext { onRemoved() }
                }
            } else {
                handleAddToLibraryResult(
                    manga = manga,
                    source = state.source,
                    result = libraryActionCoordinator.addToLibrary(
                        manga = manga,
                        checkDuplicate = checkDuplicate,
                    ),
                )
            }
        }
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        screenModelScope.launch {
            val selection = libraryActionCoordinator.categorySelection(manga)
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = selection.toCheckboxState(),
                    ),
                )
            }
        }
    }

    private suspend fun handleAddToLibraryResult(
        manga: Manga,
        source: Source,
        result: AddToLibraryResult,
    ) {
        when (result) {
            AddToLibraryResult.Added -> addTracks.bindEnhancedTrackers(manga, source)
            AddToLibraryResult.NotAdded -> {}
            is AddToLibraryResult.DuplicateFound -> {
                updateSuccessState {
                    it.copy(
                        dialog = Dialog.DuplicateManga(
                            manga = manga,
                            duplicates = result.duplicates,
                            groupTargets = result.groupTargets,
                        ),
                    )
                }
            }
            is AddToLibraryResult.NeedsCategorySelection -> {
                updateSuccessState {
                    it.copy(
                        dialog = Dialog.ChangeCategory(
                            manga = manga,
                            initialSelection = result.selection.toCheckboxState(),
                            pendingAddToGroup = result.pendingAddToGroup,
                        ),
                    )
                }
            }
        }
    }

    private fun CategorySelection.toCheckboxState(): List<CheckboxState<Category>> {
        return categories.mapAsCheckboxState { category -> category.id in selectedCategoryIds }
    }

    fun addDuplicateMangaToGroup(targets: List<DuplicateMangaGroupTargetItem>) {
        val manga = successState?.manga ?: return
        if (targets.isEmpty()) return

        screenModelScope.launchIO {
            val pendingAddToGroup = PendingAddToGroup(targets)
            when (
                val result = libraryActionCoordinator.addToLibrary(
                    manga = manga,
                    checkDuplicate = false,
                    pendingAddToGroup = pendingAddToGroup,
                )
            ) {
                AddToLibraryResult.Added -> {
                    addMangaToSelectedGroup(manga, pendingAddToGroup)
                    addTracks.bindEnhancedTrackers(manga, successState?.source ?: return@launchIO)
                }
                AddToLibraryResult.NotAdded -> {}
                is AddToLibraryResult.DuplicateFound -> {}
                is AddToLibraryResult.NeedsCategorySelection -> {
                    handleAddToLibraryResult(
                        manga = manga,
                        source = successState?.source ?: return@launchIO,
                        result = result,
                    )
                }
            }
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetFetchInterval(manga))
        }
    }

    fun showLibraryMangaGroupDialog() {
        val state = successState ?: return
        if (state.manga.isLocal() || !state.manga.favorite) return

        screenModelScope.launchIO {
            val groupId = state.libraryMangaGroupTabs.firstOrNull()?.let {
                manageLibraryMangaGroup.getGroupForManga(state.manga.id)?.id
            }
            val candidates = manageLibraryMangaGroup
                .getCandidates(anchorMangaId = state.manga.id, groupId = groupId)
                .let { candidates ->
                    libraryMangaGroupStateBuilder.candidates(
                        candidates = candidates,
                        excludedMangaId = state.manga.id,
                    )
                }

            updateSuccessState {
                it.copy(
                    dialog = Dialog.LibraryMangaGroupSetup(
                        groupId = groupId,
                        initialTitle = state.manga.title,
                        candidates = candidates,
                    ),
                )
            }
        }
    }

    fun confirmLibraryMangaGroupSources(groupId: Long?, selectedMangaIds: List<Long>) {
        val manga = successState?.manga ?: return
        val memberMangaIds = selectedMangaIds
            .filterNot { it == manga.id }
            .distinct()
        if (memberMangaIds.isEmpty()) return

        screenModelScope.launchIO {
            if (groupId == null) {
                manageLibraryMangaGroup.createGroup(
                    primaryMangaId = manga.id,
                    memberMangaIds = memberMangaIds,
                )
            } else {
                manageLibraryMangaGroup.addSources(
                    groupId = groupId,
                    memberMangaIds = memberMangaIds,
                )
            }
            val tabs = getLibraryMangaGroupTabs(manga.id)
            updateSuccessState { it.copy(dialog = null, libraryMangaGroupTabs = tabs) }
        }
    }

    fun setCurrentSourceAsPrimary() {
        val manga = successState?.manga ?: return
        screenModelScope.launchIO {
            val group = manageLibraryMangaGroup.getGroupForManga(manga.id) ?: return@launchIO
            manageLibraryMangaGroup.setPrimary(group.id, manga.id)
            val tabs = getLibraryMangaGroupTabs(manga.id)
            updateSuccessState { it.copy(libraryMangaGroupTabs = tabs) }
        }
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        screenModelScope.launchIO {
            if (
                updateManga.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    manga.copy(fetchInterval = -interval),
                )
            ) {
                val updatedManga = mangaRepository.getMangaById(manga.id)
                updateSuccessState { it.copy(manga = updatedManga) }
            }
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        downloadManager.deleteManga(state.manga, state.source)
    }

    private suspend fun getLibraryMangaGroupTabs(selectedMangaId: Long): List<LibraryMangaGroupTab> {
        val group = manageLibraryMangaGroup.getGroupForManga(selectedMangaId)
        return libraryMangaGroupStateBuilder.tabs(group = group, selectedMangaId = selectedMangaId)
    }

    fun selectLibraryMangaGroupTab(mangaId: Long) {
        if (activeMangaId.value == mangaId) return
        updateSuccessState { state ->
            state.copy(
                libraryMangaGroupTabs = state.libraryMangaGroupTabs.selectManga(mangaId),
            )
        }
        activeMangaId.value = mangaId
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        screenModelScope.launchIO {
            if (!libraryActionCoordinator.moveToCategoriesAndAddToLibrary(manga, categories)) return@launchIO

            val pendingAddToGroup = (successState?.dialog as? Dialog.ChangeCategory)?.pendingAddToGroup
            if (pendingAddToGroup != null) {
                addMangaToSelectedGroup(manga, pendingAddToGroup)
                addTracks.bindEnhancedTrackers(manga, successState?.source ?: return@launchIO)
            }
        }
    }

    private suspend fun addMangaToSelectedGroup(manga: Manga, pendingAddToGroup: PendingAddToGroup) {
        if (!libraryActionCoordinator.addMangaToSelectedGroup(manga, pendingAddToGroup)) return

        val tabs = getLibraryMangaGroupTabs(manga.id)
        updateSuccessState { it.copy(dialog = null, libraryMangaGroupTabs = tabs) }
    }

    // Manga info - end

    // Local-to-Vault Import - start

    private fun updateLocalVaultImportState(localVaultImport: LocalVaultImportState) {
        updateSuccessState { state ->
            state.copy(
                chapters = state.chapters.map {
                    it.copy(importDuplicate = it.chapter.url in localVaultImport.duplicateChapterSelectionIds)
                },
                localVaultImport = localVaultImport,
            )
        }
    }

    fun showLocalVaultTargetSetup(pendingAddToVault: Boolean) {
        localVaultImportCoordinator.showTargetSetup(pendingAddToVault)
    }

    fun openLocalVaultTargetRow(onOpenSettings: () -> Unit) {
        when (successState?.localVaultImport?.targetState) {
            LocalVaultImportTargetState.SetupContentVault -> onOpenSettings()
            null -> {}
            else -> showLocalVaultTargetSetup(pendingAddToVault = false)
        }
    }

    fun selectLocalVaultTarget(
        target: LocalVaultImportTargetSelection?,
        pendingAddToVault: Boolean,
    ) {
        localVaultImportCoordinator.selectTarget(target, pendingAddToVault)
    }

    fun startAddToVault(onOpenSettings: () -> Unit) {
        localVaultImportCoordinator.startAddToVault(onOpenSettings)
    }

    fun confirmLocalVaultReplacement() {
        localVaultImportCoordinator.confirmReplacement()
    }

    // Local-to-Vault Import - end

    // Chapters list - start

    private fun observeDownloads() {
        screenModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }

        screenModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }
    }

    private fun updateDownloadState(download: Download) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == download.chapter.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    private fun List<Chapter>.toChapterListItems(manga: Manga): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        val duplicateSelectionIds = successState?.localVaultImport?.duplicateChapterSelectionIds.orEmpty()
        return map { chapter ->
            val activeDownload = if (isLocal) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }
            val downloaded = if (isLocal) {
                true
            } else {
                downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    manga.source,
                )
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapterSelection.contains(chapter.id),
                importDuplicate = chapter.url in duplicateSelectionIds,
            )
        }
    }

    /**
     * Requests an updated list of chapters from the source.
     */
    private suspend fun fetchChaptersFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val chapters = state.source.getChapterList(state.manga.toSManga())

                val newChapters = syncChaptersWithSource.await(
                    chapters,
                    state.manga,
                    state.source,
                    manualFetch,
                )

                if (manualFetch) {
                    downloadNewChapters(newChapters)
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e

            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
            val newManga = mangaRepository.getMangaById(state.manga.id)
            updateSuccessState { it.copy(manga = newManga, isRefreshingData = false) }
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        screenModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        return successState.chapters.getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val successState = successState ?: return

        screenModelScope.launchNonCancellable {
            if (startNow) {
                val chapterId = chapters.singleOrNull()?.id ?: return@launchNonCancellable
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(chapters)
            }

            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        screenModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launchIO
            }

            val mangaId = successState?.manga?.id ?: return@launchIO
            refreshTrackers(mangaId = mangaId)

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                withUIContext {
                    context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers(
        mangaId: Long,
        refreshTracks: RefreshTracks = Injekt.get(),
    ) {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withUIContext {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private fun downloadChapters(chapters: List<Chapter>) {
        val manga = successState?.manga ?: return
        downloadManager.downloadChapters(manga, chapters)
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        screenModelScope.launchIO {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        screenModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    downloadManager.deleteChapters(
                        chapters,
                        state.manga,
                        state.source,
                    )
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        screenModelScope.launchNonCancellable {
            val manga = successState?.manga ?: return@launchNonCancellable
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            libraryPreferences.setChapterSettingsDefault(manga)
            if (applyToExisting) {
                setMangaDefaultChapterFlags.awaitAll()
            }
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        updateSuccessState { successState ->
            val newChapters = chapterSelection.toggle(
                chapters = successState.processedChapters,
                item = item,
                selected = selected,
                fromLongPress = fromLongPress,
            )
            successState.copy(chapters = newChapters)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            val newChapters = chapterSelection.toggleAll(successState.chapters, selected)
            successState.copy(chapters = newChapters)
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            val newChapters = chapterSelection.invert(successState.chapters)
            successState.copy(chapters = newChapters)
        }
    }

    // Chapters list - end

    // Track sheet - start

    private fun observeTrackers(mangaId: Long, source: Source) {
        trackerJob?.cancel()
        trackerJob = screenModelScope.launchIO {
            combine(
                getTracks.subscribe(mangaId).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                }
        }
    }

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
            val pendingAddToGroup: PendingAddToGroup? = null,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
            val groupTargets: List<DuplicateMangaGroupTargetItem> = emptyList(),
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class LocalVaultTargetSetup(
            val initialTitle: String,
            val targets: List<VaultManga>,
            val selectedTarget: LocalVaultImportTargetSelection?,
            val allowCreateNew: Boolean,
            val allowUnlink: Boolean,
            val pendingAddToVault: Boolean,
        ) : Dialog
        data class LibraryMangaGroupSetup(
            val groupId: Long?,
            val initialTitle: String,
            val candidates: List<LibraryMangaGroupCandidateItem>,
        ) : Dialog
        data class LocalVaultReplaceChapters(val chapterTitles: List<String>) : Dialog
        data class DeleteLocalManga(val manga: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteChapters(chapters)) }
    }

    fun showDeleteLocalMangaDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.DeleteLocalManga(manga)) }
    }

    fun deleteLocalManga(onDeleted: () -> Unit) {
        val manga = successState?.manga ?: return
        if (successState?.isDeletingLocalManga == true) return
        screenModelScope.launch {
            isDeletingLocalManga = true
            updateSuccessState { it.copy(isDeletingLocalManga = true) }
            val result = try {
                localMangaDeletionService.delete(manga)
            } catch (e: CancellationException) {
                isDeletingLocalManga = false
                updateSuccessState { it.copy(isDeletingLocalManga = false) }
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
                LocalMangaDeletionResult.StateCleanupFailed
            }
            if (result == LocalMangaDeletionResult.Deleted) {
                onDeleted()
                context.toast(MR.strings.local_manga_delete_complete)
                return@launch
            }
            isDeletingLocalManga = false
            updateSuccessState { it.copy(isDeletingLocalManga = false) }
            val message = when (result) {
                LocalMangaDeletionResult.BlockedByActiveReader -> MR.strings.local_manga_delete_blocked_reader
                LocalMangaDeletionResult.BlockedByActiveImport -> MR.strings.local_manga_delete_blocked_import
                LocalMangaDeletionResult.MangaDirectoryNotFound -> MR.strings.local_manga_delete_missing_folder
                LocalMangaDeletionResult.FileDeletionFailed,
                LocalMangaDeletionResult.NotLocalManga,
                LocalMangaDeletionResult.StateCleanupFailed,
                -> MR.strings.local_manga_delete_failed
                LocalMangaDeletionResult.Deleted -> error("Handled above")
            }
            snackbarHostState.showSnackbar(context.stringResource(message))
        }
    }

    fun showSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(target = manga, current = duplicate)) }
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        screenModelScope.launchIO {
            val manga = successState?.manga ?: return@launchIO
            setExcludedScanlators.await(manga.id, excludedScanlators)
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val isDeletingLocalManga: Boolean = false,
            val hasPromptedToAddBefore: Boolean = false,
            val hideMissingChapters: Boolean = false,
            val canEditLocalMetadata: Boolean = false,
            val localVaultImport: LocalVaultImportState? = null,
            val libraryMangaGroupTabs: List<LibraryMangaGroupTab> = emptyList(),
        ) : State {
            val processedChapters by lazy {
                MangaChapterListProcessor.process(chapters, manga)
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val chapterListItems by lazy {
                MangaChapterListProcessor.withMissingChapterSeparators(
                    processedChapters = processedChapters,
                    manga = manga,
                    hideMissingChapters = hideMissingChapters,
                )
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val downloadProgress: Int,
        val selected: Boolean = false,
        val importDuplicate: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}
