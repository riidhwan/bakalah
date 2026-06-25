package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
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
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import kotlin.coroutines.cancellation.CancellationException

class MangaScreenModel(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val mangaId: Long,
    private val isFromSource: Boolean,
    private val dependencies: MangaScreenModelDependencies = MangaScreenModelDependencies(),
) : StateScreenModel<MangaScreenModel.State>(State.Loading) {
    private val libraryPreferences = dependencies.libraryPreferences
    private val trackPreferences = dependencies.trackPreferences
    private val readerPreferences = dependencies.readerPreferences
    private val setExcludedScanlators = dependencies.setExcludedScanlators
    private val updateManga = dependencies.updateManga
    private val mangaRepository = dependencies.mangaRepository

    private val successState: State.Success?
        get() = state.value as? State.Success

    private val _uiEffects = MutableSharedFlow<UiEffect>()
    val uiEffects = _uiEffects.asSharedFlow()

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    fun showSnackbar(message: String) {
        emitUiEffect(UiEffect.ShowSnackbar(message = message))
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

    private val mangaStateAssembler = dependencies.mangaStateAssembler

    private val localVaultImportCoordinator = dependencies.localVaultImportCoordinator(
        runtime = MangaLocalVaultImportCoordinator.Runtime(
            context = context,
            lifecycle = lifecycle,
            screenModelScope = screenModelScope,
        ),
        callbacks = MangaLocalVaultImportCoordinator.Callbacks(
            getState = { successState },
            setLocalVaultImportState = ::updateLocalVaultImportState,
            showDialog = ::showVaultDialog,
            dismissDialog = ::dismissDialog,
            selectedChapters = chapterSelection::selectedChapters,
            clearSelection = { toggleAllSelection(false) },
            showUiEffect = ::emitUiEffect,
        ),
    )

    private val libraryGroupCoordinator = dependencies.libraryGroupCoordinator
    private val libraryWorkflowCoordinator = dependencies.libraryWorkflowCoordinator
    private val sessionCoordinator = dependencies.sessionCoordinator
    private val chapterSettingsCoordinator = dependencies.chapterSettingsCoordinator
    private val downloadCoordinator = dependencies.downloadCoordinator
    private val sourceRefreshCoordinator = dependencies.sourceRefreshCoordinator
    private val trackingCoordinator = dependencies.trackingCoordinator
    private val localMangaDeletionCoordinator = dependencies.localMangaDeletionCoordinator
    private val chapterActionCoordinator = dependencies.chapterActionCoordinator(
        runtime = MangaChapterActionCoordinator.Runtime(
            screenModelScope = screenModelScope,
        ),
        callbacks = MangaChapterActionCoordinator.Callbacks(
            getState = { successState },
            updateState = { transform -> updateSuccessState(transform) },
            updateDownloadState = ::updateDownloadState,
            toggleAllSelection = ::toggleAllSelection,
            toggleFavorite = ::toggleFavorite,
            trackChapter = { update -> trackingCoordinator.trackChapter(context, update) },
            showActionEffect = ::applyChapterActionEffect,
        ),
        skipFiltered = { skipFiltered },
        autoTrackState = { autoTrackState },
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

    private fun emitUiEffect(effect: UiEffect) {
        screenModelScope.launch {
            _uiEffects.emit(effect)
        }
    }

    private suspend fun applyChapterActionEffect(effect: MangaChapterActionEffect) {
        when (effect) {
            MangaChapterActionEffect.PromptAddToLibrary -> {
                emitUiEffect(
                    UiEffect.ShowSnackbar(
                        message = context.stringResource(MR.strings.snack_add_to_library),
                        actionLabel = context.stringResource(MR.strings.action_add),
                        withDismissAction = true,
                        onAction = {
                            if (successState?.manga?.favorite != true) {
                                toggleFavorite()
                            }
                        },
                    ),
                )
            }
            is MangaChapterActionEffect.ShowTrackerRefreshFailure -> {
                emitUiEffect(
                    UiEffect.ShowToast(
                        context.stringResource(
                            MR.strings.track_error,
                            effect.failure.trackerName,
                            effect.failure.error.message ?: "",
                        ),
                    ),
                )
            }
            is MangaChapterActionEffect.ShowTrackerUpdated -> {
                emitUiEffect(
                    UiEffect.ShowToast(
                        context.stringResource(
                            MR.strings.trackers_updated_summary,
                            effect.chapterNumber,
                        ),
                    ),
                )
            }
            is MangaChapterActionEffect.ConfirmTrackerUpdate -> {
                emitUiEffect(
                    UiEffect.ShowSnackbar(
                        message = context.stringResource(
                            MR.strings.confirm_tracker_update,
                            effect.update.chapterNumber.toInt(),
                        ),
                        actionLabel = context.stringResource(MR.strings.action_ok),
                        duration = SnackbarDuration.Short,
                        withDismissAction = true,
                        onAction = {
                            screenModelScope.launchIO {
                                trackingCoordinator.trackChapter(context, effect.update)
                            }
                        },
                    ),
                )
            }
        }
    }

    init {
        sessionCoordinator.start(
            runtime = MangaSessionCoordinator.Runtime(
                screenModelScope = screenModelScope,
            ),
            activeMangaId = activeMangaId,
            callbacks = MangaSessionCoordinator.Callbacks(
                isDeletingLocalManga = { isDeletingLocalManga },
                onSnapshot = ::applyLoadSnapshot,
                onRefreshOnLoad = ::refreshOnLoad,
                onExcludedScanlators = ::applyExcludedScanlators,
                onAvailableScanlators = ::applyAvailableScanlators,
            ),
        )
        observeDownloads()
    }

    private suspend fun applyLoadSnapshot(snapshot: MangaLoadSnapshot) {
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
    }

    private suspend fun refreshOnLoad(snapshot: MangaLoadSnapshot) {
        try {
            coroutineScope {
                val fetchFromSourceTasks = listOf(
                    async { if (snapshot.needRefreshInfo) fetchMangaFromSource() },
                    async { if (snapshot.needRefreshChapter) fetchChaptersFromSource() },
                )
                fetchFromSourceTasks.awaitAll()
            }
        } finally {
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    private fun applyExcludedScanlators(excludedScanlators: Set<String>) {
        updateSuccessState {
            it.copy(excludedScanlators = excludedScanlators)
        }
    }

    private fun applyAvailableScanlators(availableScanlators: Set<String>) {
        updateSuccessState {
            it.copy(availableScanlators = availableScanlators)
        }
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
            is MangaSourceRefreshResult.Failed -> {
                showSourceRefreshError(result.error)
            }
        }
    }

    private fun showSourceRefreshError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        showSourceRefreshMessage(with(context) { error.formattedMessage })
    }

    private fun showSourceRefreshMessage(message: String) {
        emitUiEffect(UiEffect.ShowSnackbar(message = message))
    }

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                if (hasDownloads()) {
                    emitUiEffect(
                        UiEffect.ShowSnackbar(
                            message = context.stringResource(MR.strings.delete_downloads_for_manga),
                            actionLabel = context.stringResource(MR.strings.action_delete),
                            withDismissAction = true,
                            onAction = ::deleteDownloads,
                        ),
                    )
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
            val effect = libraryWorkflowCoordinator.toggleFavorite(
                manga = state.manga,
                source = state.source,
                isFavorited = isFavorited,
                checkDuplicate = checkDuplicate,
            )
            applyLibraryWorkflowEffect(
                effect = effect,
                onRemoved = {
                    withUIContext { onRemoved() }
                },
            )
        }
    }

    private suspend fun applyLibraryWorkflowEffect(
        effect: MangaLibraryWorkflowEffect,
        onRemoved: suspend () -> Unit = {},
    ) {
        when (effect) {
            MangaLibraryWorkflowEffect.Added,
            MangaLibraryWorkflowEffect.None,
            -> Unit
            MangaLibraryWorkflowEffect.Removed -> onRemoved()
            is MangaLibraryWorkflowEffect.ShowChangeCategory -> {
                showLibraryDialog(
                    MangaLibraryDialog.ChangeCategory(
                        manga = effect.manga,
                        initialSelection = effect.selection.toCheckboxState(),
                        pendingAddToGroup = effect.pendingAddToGroup,
                    ),
                )
            }
            is MangaLibraryWorkflowEffect.ShowDuplicateManga -> {
                showLibraryDialog(
                    MangaLibraryDialog.DuplicateManga(
                        manga = effect.manga,
                        duplicates = effect.duplicates,
                        groupTargets = effect.groupTargets,
                    ),
                )
            }
            is MangaLibraryWorkflowEffect.UpdateGroupTabs -> {
                updateSuccessState {
                    it.copy(
                        dialogs = if (effect.dismissDialog) {
                            it.dialogs.copy(library = null)
                        } else {
                            it.dialogs
                        },
                        libraryMangaGroupTabs = effect.tabs,
                    )
                }
            }
        }
    }

    private fun CategorySelection.toCheckboxState(): List<CheckboxState<Category>> {
        return categories.mapAsCheckboxState { category -> category.id in selectedCategoryIds }
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        screenModelScope.launchIO {
            applyLibraryWorkflowEffect(libraryWorkflowCoordinator.showChangeCategoryDialog(manga))
        }
    }

    fun addDuplicateMangaToGroup(targets: List<DuplicateMangaGroupTargetItem>) {
        val state = successState ?: return

        screenModelScope.launchIO {
            applyLibraryWorkflowEffect(
                libraryWorkflowCoordinator.addDuplicateMangaToGroup(
                    manga = state.manga,
                    source = state.source,
                    targets = targets,
                ),
            )
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        showLibraryDialog(MangaLibraryDialog.SetFetchInterval(manga))
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
                it.withLibraryDialog(
                    MangaLibraryDialog.LibraryMangaGroupSetup(
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
            updateSuccessState { it.dismissDialogs().copy(libraryMangaGroupTabs = tabs) }
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
        val state = successState ?: return
        val pendingAddToGroup = (state.dialogs.library as? MangaLibraryDialog.ChangeCategory)?.pendingAddToGroup
        screenModelScope.launchIO {
            applyLibraryWorkflowEffect(
                libraryWorkflowCoordinator.moveMangaToCategoriesAndAddToLibrary(
                    manga = manga,
                    source = state.source,
                    categories = categories,
                    pendingAddToGroup = pendingAddToGroup,
                ),
            )
        }
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
            emitUiEffect(UiEffect.ShowSnackbar(context.stringResource(MR.strings.chapter_settings_updated)))
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
                            tracking = MangaTrackingUiState(
                                count = trackingState.trackingCount,
                                hasLoggedInTrackers = trackingState.hasLoggedInTrackers,
                            ),
                        )
                    }
                }
        }
    }

    // Track sheet - end

    fun dismissDialog() {
        updateSuccessState { it.dismissDialogs() }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        showChapterDialog(MangaChapterDialog.DeleteChapters(chapters))
    }

    fun showDeleteLocalMangaDialog() {
        val manga = successState?.manga ?: return
        showLocalDialog(MangaLocalDialog.DeleteLocalManga(manga))
    }

    fun deleteLocalManga(onDeleted: () -> Unit) {
        val manga = successState?.manga ?: return
        if (successState?.localDeletion?.isDeleting == true) return
        screenModelScope.launch {
            isDeletingLocalManga = true
            updateSuccessState { it.copy(localDeletion = MangaLocalDeletionUiState(isDeleting = true)) }
            try {
                when (val result = localMangaDeletionCoordinator.delete(manga)) {
                    MangaLocalDeletionOutcome.Deleted -> {
                        onDeleted()
                        emitUiEffect(UiEffect.ShowToast(context.stringResource(MR.strings.local_manga_delete_complete)))
                        return@launch
                    }
                    is MangaLocalDeletionOutcome.Failed -> {
                        isDeletingLocalManga = false
                        updateSuccessState { it.copy(localDeletion = MangaLocalDeletionUiState(isDeleting = false)) }
                        emitUiEffect(UiEffect.ShowSnackbar(context.stringResource(result.message)))
                    }
                }
            } catch (e: CancellationException) {
                isDeletingLocalManga = false
                updateSuccessState { it.copy(localDeletion = MangaLocalDeletionUiState(isDeleting = false)) }
                throw e
            }
        }
    }

    fun showSettingsDialog() {
        showChapterDialog(MangaChapterDialog.SettingsSheet)
    }

    fun showTrackDialog() {
        showTrackingDialog(MangaTrackingDialog.TrackSheet)
    }

    fun showCoverDialog() {
        showCoverDialog(MangaCoverDialogState.FullCover)
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        showMigrationDialog(MangaMigrationDialog.Migrate(target = manga, current = duplicate))
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        screenModelScope.launchIO {
            val manga = successState?.manga ?: return@launchIO
            setExcludedScanlators.await(manga.id, excludedScanlators)
        }
    }

    sealed interface UiEffect {
        data class ShowSnackbar(
            val message: String,
            val actionLabel: String? = null,
            val withDismissAction: Boolean = false,
            val duration: SnackbarDuration = SnackbarDuration.Short,
            val onAction: (() -> Unit)? = null,
        ) : UiEffect

        data class ShowToast(val message: String) : UiEffect
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
            val tracking: MangaTrackingUiState = MangaTrackingUiState(),
            val isRefreshingData: Boolean = false,
            val dialogs: MangaDialogState = MangaDialogState(),
            val localDeletion: MangaLocalDeletionUiState = MangaLocalDeletionUiState(),
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

            fun dismissDialogs(): Success {
                return copy(dialogs = dialogs.dismissed())
            }

            fun withLibraryDialog(dialog: MangaLibraryDialog): Success {
                return copy(dialogs = dialogs.withLibrary(dialog))
            }

            fun withChapterDialog(dialog: MangaChapterDialog): Success {
                return copy(dialogs = dialogs.withChapter(dialog))
            }

            fun withVaultDialog(dialog: MangaVaultDialog): Success {
                return copy(dialogs = dialogs.withVault(dialog))
            }

            fun withLocalDialog(dialog: MangaLocalDialog): Success {
                return copy(dialogs = dialogs.withLocal(dialog))
            }

            fun withMigrationDialog(dialog: MangaMigrationDialog): Success {
                return copy(dialogs = dialogs.withMigration(dialog))
            }

            fun withTrackingDialog(dialog: MangaTrackingDialog): Success {
                return copy(dialogs = dialogs.withTracking(dialog))
            }

            fun withCoverDialog(dialog: MangaCoverDialogState): Success {
                return copy(dialogs = dialogs.withCover(dialog))
            }
        }
    }

    private fun showLibraryDialog(dialog: MangaLibraryDialog) {
        updateSuccessState { it.withLibraryDialog(dialog) }
    }

    private fun showChapterDialog(dialog: MangaChapterDialog) {
        updateSuccessState { it.withChapterDialog(dialog) }
    }

    private fun showVaultDialog(dialog: MangaVaultDialog) {
        updateSuccessState { it.withVaultDialog(dialog) }
    }

    private fun showLocalDialog(dialog: MangaLocalDialog) {
        updateSuccessState { it.withLocalDialog(dialog) }
    }

    private fun showMigrationDialog(dialog: MangaMigrationDialog) {
        updateSuccessState { it.withMigrationDialog(dialog) }
    }

    private fun showTrackingDialog(dialog: MangaTrackingDialog) {
        updateSuccessState { it.withTrackingDialog(dialog) }
    }

    private fun showCoverDialog(dialog: MangaCoverDialogState) {
        updateSuccessState { it.withCoverDialog(dialog) }
    }
}

@Immutable
data class MangaTrackingUiState(
    val count: Int = 0,
    val hasLoggedInTrackers: Boolean = false,
)

@Immutable
data class MangaLocalDeletionUiState(
    val isDeleting: Boolean = false,
)

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
