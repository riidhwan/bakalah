package eu.kanade.tachiyomi.ui.manga

import android.content.Context
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
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.local.LocalMangaDeletionResult
import eu.kanade.tachiyomi.data.local.LocalMangaDeletionService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
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
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val manageLibraryMangaGroup: ManageLibraryMangaGroup = Injekt.get(),
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
    private var trackerJob: Job? = null

    @Volatile
    private var isDeletingLocalManga = false

    private val mangaStateAssembler = MangaStateAssembler(
        libraryPreferences = libraryPreferences,
        localSourceFileSystem = localSourceFileSystem,
    )

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

    private val libraryGroupCoordinator = MangaLibraryGroupCoordinator(
        MangaLibraryGroupCoordinator.Dependencies(
            manageLibraryMangaGroup = manageLibraryMangaGroup,
            libraryMangaGroupStateBuilder = libraryMangaGroupStateBuilder,
        ),
    )
    private val loadCoordinator = MangaLoadCoordinator(
        MangaLoadCoordinator.Dependencies(
            getMangaAndChapters = getMangaAndChapters,
            downloadCache = downloadCache,
            downloadManager = downloadManager,
            setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
            sourceManager = sourceManager,
            libraryGroupCoordinator = libraryGroupCoordinator,
        ),
    )

    private val chapterSettingsCoordinator = MangaChapterSettingsCoordinator(
        MangaChapterSettingsCoordinator.Dependencies(
            libraryPreferences = libraryPreferences,
            setMangaChapterFlags = setMangaChapterFlags,
            setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
        ),
    )
    private val downloadCoordinator = MangaDownloadCoordinator(downloadManager)

    private val sourceRefreshCoordinator = MangaSourceRefreshCoordinator(
        MangaSourceRefreshCoordinator.Dependencies(
            updateManga = updateManga,
            syncChaptersWithSource = Injekt.get(),
            mangaRepository = mangaRepository,
            filterChaptersForDownload = Injekt.get(),
        ),
    )
    private val trackingCoordinator = MangaTrackingCoordinator(
        MangaTrackingCoordinator.Dependencies(
            getTracks = getTracks,
            trackerManager = Injekt.get(),
        ),
    )
    private val chapterActionCoordinator = MangaChapterActionCoordinator(
        runtime = MangaChapterActionCoordinator.Runtime(
            context = context,
            screenModelScope = screenModelScope,
            snackbarHostState = snackbarHostState,
        ),
        dependencies = MangaChapterActionCoordinator.Dependencies(
            setReadStatus = setReadStatus,
            updateChapter = updateChapter,
            skipFiltered = { skipFiltered },
            autoTrackState = { autoTrackState },
        ),
        coordinators = MangaChapterActionCoordinator.Coordinators(
            downloadCoordinator = downloadCoordinator,
            trackingCoordinator = trackingCoordinator,
        ),
        callbacks = MangaChapterActionCoordinator.Callbacks(
            getState = { successState },
            updateState = { transform -> updateSuccessState(transform) },
            updateDownloadState = ::updateDownloadState,
            toggleAllSelection = ::toggleAllSelection,
            toggleFavorite = ::toggleFavorite,
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
            loadCoordinator.observe(activeMangaId)
                .catch { error ->
                    if (!isDeletingLocalManga) throw error
                }
                .flowWithLifecycle(lifecycle)
                .collectLatest { snapshot ->
                    if (snapshot.isMangaSwitch) {
                        chapterSelection.clear()
                    }

                    val chapterItems = snapshot.chapters.toChapterListItems(snapshot.manga)

                    mutableState.update { previousState ->
                        mangaStateAssembler.successState(
                            previousState = previousState,
                            manga = snapshot.manga,
                            source = snapshot.source,
                            isFromSource = isFromSource,
                            chapters = chapterItems,
                            isRefreshingData = snapshot.needRefreshInfo || snapshot.needRefreshChapter,
                            isMangaSwitch = snapshot.isMangaSwitch,
                            libraryMangaGroupTabs = snapshot.libraryMangaGroupTabs,
                        )
                    }

                    if (snapshot.isMangaSwitch) {
                        localVaultImportCoordinator.restartObservation(snapshot.manga, snapshot.source)
                        observeTrackers(snapshot.manga.id, snapshot.source)
                    }

                    if (loadCoordinator.takeRefreshOnLoad(snapshot, screenModelScope.isActive)) {
                        try {
                            val fetchFromSourceTasks = listOf(
                                async { if (snapshot.needRefreshInfo) fetchMangaFromSource() },
                                async { if (snapshot.needRefreshChapter) fetchChaptersFromSource() },
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
        when (
            val result = sourceRefreshCoordinator.fetchMangaFromSource(
                manga = state.manga,
                source = state.source,
                manualFetch = manualFetch,
            )
        ) {
            MangaSourceRefreshResult.Success,
            MangaSourceRefreshResult.IgnoredEarlyHints,
            -> Unit
            is MangaSourceRefreshResult.Failed -> showSourceRefreshError(result.error)
        }
    }

    private fun showSourceRefreshError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        showSourceRefreshMessage(with(context) { error.formattedMessage })
    }

    private fun showSourceRefreshMessage(message: String) {
        screenModelScope.launch {
            snackbarHostState.showSnackbar(message = message)
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
            val setup = libraryGroupCoordinator.setup(
                manga = state.manga,
                currentTabs = state.libraryMangaGroupTabs,
            )
            updateSuccessState {
                it.copy(
                    dialog = Dialog.LibraryMangaGroupSetup(
                        groupId = setup.groupId,
                        initialTitle = setup.initialTitle,
                        candidates = setup.candidates,
                    ),
                )
            }
        }
    }

    fun confirmLibraryMangaGroupSources(groupId: Long?, selectedMangaIds: List<Long>) {
        val manga = successState?.manga ?: return

        screenModelScope.launchIO {
            val tabs = libraryGroupCoordinator.addSources(
                manga = manga,
                groupId = groupId,
                selectedMangaIds = selectedMangaIds,
            ) ?: return@launchIO
            updateSuccessState { it.copy(dialog = null, libraryMangaGroupTabs = tabs) }
        }
    }

    fun setCurrentSourceAsPrimary() {
        val manga = successState?.manga ?: return
        screenModelScope.launchIO {
            val tabs = libraryGroupCoordinator.setPrimary(manga) ?: return@launchIO
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
        return downloadCoordinator.hasDownloads(manga)
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        downloadCoordinator.deleteMangaDownloads(state.manga, state.source)
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

        val tabs = libraryGroupCoordinator.tabs(manga.id)
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
            downloadCoordinator.observeDownloadUpdates(activeMangaId = { successState?.manga?.id })
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
            successState.copy(
                chapters = downloadCoordinator.updateDownloadState(successState.chapters, download),
            )
        }
    }

    private fun List<Chapter>.toChapterListItems(manga: Manga): List<ChapterList.Item> {
        val duplicateSelectionIds = successState?.localVaultImport?.duplicateChapterSelectionIds.orEmpty()
        return downloadCoordinator.chapterListItems(
            chapters = this,
            manga = manga,
            isSelected = chapterSelection::contains,
            duplicateSelectionIds = duplicateSelectionIds,
        )
    }

    /**
     * Requests an updated list of chapters from the source.
     */
    private suspend fun fetchChaptersFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        when (
            val result = sourceRefreshCoordinator.fetchChaptersFromSource(
                manga = state.manga,
                source = state.source,
                manualFetch = manualFetch,
            )
        ) {
            is ChapterSourceRefreshResult.Success -> {
                if (result.chaptersToDownload.isNotEmpty()) {
                    chapterActionCoordinator.downloadChapters(result.chaptersToDownload)
                }
            }
            is ChapterSourceRefreshResult.NoChapters -> {
                showSourceRefreshMessage(context.stringResource(MR.strings.no_chapters_error))
                updateSuccessState { it.copy(manga = result.latestManga, isRefreshingData = false) }
            }
            is ChapterSourceRefreshResult.Failed -> {
                showSourceRefreshError(result.error)
                updateSuccessState { it.copy(manga = result.latestManga, isRefreshingData = false) }
            }
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        chapterActionCoordinator.chapterSwipe(chapterItem, swipeAction)
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        return chapterActionCoordinator.getNextUnreadChapter()
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        chapterActionCoordinator.runChapterDownloadActions(items, action)
    }

    fun runDownloadAction(action: DownloadAction) {
        val manga = successState?.manga ?: return
        chapterActionCoordinator.runDownloadAction(
            action = action,
            manga = manga,
            allChapters = allChapters.orEmpty(),
            filteredChapters = filteredChapters.orEmpty(),
        )
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        chapterActionCoordinator.markPreviousChapterRead(
            pointer = pointer,
            manga = manga,
            filteredChapters = filteredChapters.orEmpty(),
        )
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        chapterActionCoordinator.markChaptersRead(chapters, read)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        chapterActionCoordinator.bookmarkChapters(chapters, bookmarked)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        chapterActionCoordinator.deleteChapters(chapters)
    }

    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            chapterSettingsCoordinator.setUnreadFilter(manga, state)
        }
    }

    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            chapterSettingsCoordinator.setDownloadedFilter(manga, state)
        }
    }

    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            chapterSettingsCoordinator.setBookmarkedFilter(manga, state)
        }
    }

    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            chapterSettingsCoordinator.setDisplayMode(manga, mode)
        }
    }

    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            chapterSettingsCoordinator.setSorting(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            chapterSettingsCoordinator.setCurrentSettingsAsDefault(manga, applyToExisting)
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            chapterSettingsCoordinator.resetToDefaultSettings(manga)
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
            trackingCoordinator.observeTrackingState(mangaId, source)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { trackingState ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingState.trackingCount,
                            hasLoggedInTrackers = trackingState.hasLoggedInTrackers,
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
