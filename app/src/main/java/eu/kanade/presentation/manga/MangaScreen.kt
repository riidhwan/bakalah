package eu.kanade.presentation.manga

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.manga.model.ChapterList
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.shouldExpandFAB
import tachiyomi.source.local.isLocal
import java.time.Instant

@Composable
fun MangaScreen(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    isTabletUi: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    onTagSearch: (String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    onCoverClicked: () -> Unit,

    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditFetchIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditLocalMetadataClicked: (() -> Unit)?,
    onDeleteLocalMangaClicked: (() -> Unit)?,
    onVaultTargetClicked: () -> Unit,
    onEditNotesClicked: () -> Unit,
    onSourceTabClicked: (Long) -> Unit,
    onUseAsPrimarySourceClicked: (() -> Unit)?,
    onAddSourceClicked: (() -> Unit)?,
    onSetAsPrimarySourceClicked: (() -> Unit)?,

    onAddToVaultClicked: () -> Unit,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val context = LocalContext.current
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    if (!isTabletUi) {
        MangaScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onEditLocalMetadataClicked = onEditLocalMetadataClicked,
            onDeleteLocalMangaClicked = onDeleteLocalMangaClicked,
            onVaultTargetClicked = onVaultTargetClicked,
            onEditNotesClicked = onEditNotesClicked,
            onSourceTabClicked = onSourceTabClicked,
            onUseAsPrimarySourceClicked = onUseAsPrimarySourceClicked,
            onAddSourceClicked = onAddSourceClicked,
            onSetAsPrimarySourceClicked = onSetAsPrimarySourceClicked,
            onAddToVaultClicked = onAddToVaultClicked,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onChapterSwipe = onChapterSwipe,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
        )
    } else {
        MangaScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            nextUpdate = nextUpdate,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onEditLocalMetadataClicked = onEditLocalMetadataClicked,
            onDeleteLocalMangaClicked = onDeleteLocalMangaClicked,
            onVaultTargetClicked = onVaultTargetClicked,
            onEditNotesClicked = onEditNotesClicked,
            onSourceTabClicked = onSourceTabClicked,
            onUseAsPrimarySourceClicked = onUseAsPrimarySourceClicked,
            onAddSourceClicked = onAddSourceClicked,
            onSetAsPrimarySourceClicked = onSetAsPrimarySourceClicked,
            onAddToVaultClicked = onAddToVaultClicked,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onChapterSwipe = onChapterSwipe,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
        )
    }
}

@Composable
private fun MangaScreenSmallImpl(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    onCoverClicked: () -> Unit,

    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditLocalMetadataClicked: (() -> Unit)?,
    onDeleteLocalMangaClicked: (() -> Unit)?,
    onVaultTargetClicked: () -> Unit,
    onEditNotesClicked: () -> Unit,
    onSourceTabClicked: (Long) -> Unit,
    onUseAsPrimarySourceClicked: (() -> Unit)?,
    onAddSourceClicked: (() -> Unit)?,
    onSetAsPrimarySourceClicked: (() -> Unit)?,

    onAddToVaultClicked: () -> Unit,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val chapterListState = rememberLazyListState()

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    Scaffold(
        topBar = {
            val selectedChapterCount: Int = remember(chapters) {
                chapters.count { it.selected }
            }
            val isFirstItemVisible by remember {
                derivedStateOf { chapterListState.firstVisibleItemIndex == 0 }
            }
            val isFirstItemScrolled by remember {
                derivedStateOf { chapterListState.firstVisibleItemScrollOffset > 0 }
            }
            val titleAlpha by animateFloatAsState(
                if (!isFirstItemVisible) 1f else 0f,
                label = "Top Bar Title",
            )
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "Top Bar Background",
            )
            MangaToolbar(
                title = state.manga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickFilter = onFilterClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditLocalMetadata = onEditLocalMetadataClicked,
                onClickDeleteLocalManga = onDeleteLocalMangaClicked,
                onClickEditNotes = onEditNotesClicked,
                onClickUseAsPrimarySource = onUseAsPrimarySourceClicked,
                onClickAddSource = onAddSourceClicked,
                onClickSetAsPrimarySource = onSetAsPrimarySourceClicked,
                actionModeCounter = selectedChapterCount,
                onCancelActionMode = { onAllChapterSelected(false) },
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        bottomBar = {
            val selectedChapters = remember(chapters) {
                chapters.filter { it.selected }
            }
            SharedMangaBottomActionMenu(
                selected = selectedChapters,
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                onAddToVaultClicked = onAddToVaultClicked.takeIf { state.localVaultImport != null },
                fillFraction = 1f,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = remember(chapters) {
                chapters.fastAny { !it.chapter.read } && !isAnySelected
            }
            SmallExtendedFloatingActionButton(
                text = {
                    val isReading = remember(state.chapters) {
                        state.chapters.fastAny { it.chapter.read }
                    }
                    Text(
                        text = stringResource(if (isReading) MR.strings.action_resume else MR.strings.action_start),
                    )
                },
                icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = chapterListState.shouldExpandFAB(),
                modifier = Modifier.animateFloatingActionButton(
                    visible = isFABVisible,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()
        val hasSourceTabs = state.libraryMangaGroupTabs.isNotEmpty()

        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(top = topPadding),
        ) {
            val layoutDirection = LocalLayoutDirection.current
            Column(modifier = Modifier.fillMaxHeight()) {
                if (hasSourceTabs) {
                    LibraryMangaGroupTabs(
                        tabs = state.libraryMangaGroupTabs,
                        onSourceTabClicked = onSourceTabClicked,
                        modifier = Modifier.padding(top = topPadding),
                    )
                }
                MangaSourceContent(
                    state = state,
                    modifier = Modifier.weight(1f),
                ) { targetState ->
                    val targetChapters = targetState.processedChapters
                    val targetListItem = targetState.chapterListItems
                    val targetIsAnySelected = targetState.isAnySelected

                    VerticalFastScroller(
                        listState = chapterListState,
                        topContentPadding = if (hasSourceTabs) 0.dp else topPadding,
                        endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            state = chapterListState,
                            contentPadding = PaddingValues(
                                start = contentPadding.calculateStartPadding(layoutDirection),
                                end = contentPadding.calculateEndPadding(layoutDirection),
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        ) {
                            item(
                                key = MangaScreenItem.INFO_BOX,
                                contentType = MangaScreenItem.INFO_BOX,
                            ) {
                                MangaInfoBox(
                                    isTabletUi = false,
                                    appBarPadding = if (hasSourceTabs) 0.dp else topPadding,
                                    manga = targetState.manga,
                                    sourceName = remember(targetState.source) {
                                        targetState.source.getNameForMangaInfo()
                                    },
                                    isStubSource = remember(targetState.source) { targetState.source is StubSource },
                                    onCoverClick = onCoverClicked,
                                    doSearch = onSearch,
                                    localVaultImport = targetState.localVaultImport,
                                    onVaultTargetClick = onVaultTargetClicked,
                                )
                            }

                            if (!targetState.source.isLocal()) {
                                item(
                                    key = MangaScreenItem.ACTION_ROW,
                                    contentType = MangaScreenItem.ACTION_ROW,
                                ) {
                                    MangaActionRow(
                                        favorite = targetState.manga.favorite,
                                        trackingCount = targetState.tracking.count,
                                        nextUpdate = nextUpdate,
                                        isUserIntervalMode = targetState.manga.fetchInterval < 0,
                                        onAddToLibraryClicked = onAddToLibraryClicked,
                                        onWebViewClicked = onWebViewClicked,
                                        onWebViewLongClicked = onWebViewLongClicked,
                                        onTrackingClicked = onTrackingClicked,
                                        onEditIntervalClicked = onEditIntervalClicked,
                                        onEditCategory = onEditCategoryClicked,
                                    )
                                }
                            }

                            item(
                                key = MangaScreenItem.DESCRIPTION_WITH_TAG,
                                contentType = MangaScreenItem.DESCRIPTION_WITH_TAG,
                            ) {
                                ExpandableMangaDescription(
                                    defaultExpandState = targetState.isFromSource,
                                    description = targetState.manga.description,
                                    tagsProvider = { targetState.manga.genre },
                                    notes = targetState.manga.notes,
                                    onTagSearch = onTagSearch,
                                    onCopyTagToClipboard = onCopyTagToClipboard,
                                    onEditNotes = onEditNotesClicked,
                                )
                            }

                            item(
                                key = MangaScreenItem.CHAPTER_HEADER,
                                contentType = MangaScreenItem.CHAPTER_HEADER,
                            ) {
                                val missingChapterCount = remember(targetChapters) {
                                    targetChapters.map { it.chapter.chapterNumber }.missingChaptersCount()
                                }
                                ChapterHeader(
                                    enabled = !targetIsAnySelected,
                                    chapterCount = targetChapters.size,
                                    missingChapterCount = missingChapterCount,
                                    onClick = onFilterClicked,
                                )
                            }

                            sharedChapterItems(
                                manga = targetState.manga,
                                chapters = targetListItem,
                                isAnyChapterSelected = targetChapters.fastAny { it.selected },
                                chapterSwipeStartAction = chapterSwipeStartAction,
                                chapterSwipeEndAction = chapterSwipeEndAction,
                                onChapterClicked = onChapterClicked,
                                onDownloadChapter = onDownloadChapter,
                                onChapterSelected = onChapterSelected,
                                onChapterSwipe = onChapterSwipe,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MangaScreenLargeImpl(
    state: MangaScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditLocalMetadataClicked: (() -> Unit)?,
    onDeleteLocalMangaClicked: (() -> Unit)?,
    onVaultTargetClicked: () -> Unit,
    onEditNotesClicked: () -> Unit,
    onSourceTabClicked: (Long) -> Unit,
    onUseAsPrimarySourceClicked: (() -> Unit)?,
    onAddSourceClicked: (() -> Unit)?,
    onSetAsPrimarySourceClicked: (() -> Unit)?,

    // For bottom action menu
    onAddToVaultClicked: () -> Unit,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For swipe actions
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    var topBarHeight by remember { mutableIntStateOf(0) }

    val chapterListState = rememberLazyListState()

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    Scaffold(
        topBar = {
            val selectedChapterCount = remember(chapters) {
                chapters.count { it.selected }
            }
            MangaToolbar(
                modifier = Modifier.onSizeChanged { topBarHeight = it.height },
                title = state.manga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickFilter = onFilterButtonClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditLocalMetadata = onEditLocalMetadataClicked,
                onClickDeleteLocalManga = onDeleteLocalMangaClicked,
                onClickEditNotes = onEditNotesClicked,
                onClickUseAsPrimarySource = onUseAsPrimarySourceClicked,
                onClickAddSource = onAddSourceClicked,
                onClickSetAsPrimarySource = onSetAsPrimarySourceClicked,
                onCancelActionMode = { onAllChapterSelected(false) },
                actionModeCounter = selectedChapterCount,
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                val selectedChapters = remember(chapters) {
                    chapters.filter { it.selected }
                }
                SharedMangaBottomActionMenu(
                    selected = selectedChapters,
                    onMultiBookmarkClicked = onMultiBookmarkClicked,
                    onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                    onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                    onDownloadChapter = onDownloadChapter,
                    onMultiDeleteClicked = onMultiDeleteClicked,
                    onAddToVaultClicked = onAddToVaultClicked.takeIf { state.localVaultImport != null },
                    fillFraction = 0.5f,
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = remember(chapters) {
                chapters.fastAny { !it.chapter.read } && !isAnySelected
            }
            SmallExtendedFloatingActionButton(
                text = {
                    val isReading = remember(state.chapters) {
                        state.chapters.fastAny { it.chapter.read }
                    }
                    Text(
                        text = stringResource(
                            if (isReading) MR.strings.action_resume else MR.strings.action_start,
                        ),
                    )
                },
                icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = chapterListState.shouldExpandFAB(),
                modifier = Modifier.animateFloatingActionButton(
                    visible = isFABVisible,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()
        val hasSourceTabs = state.libraryMangaGroupTabs.isNotEmpty()

        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(
                start = insetPadding.calculateStartPadding(layoutDirection),
                top = with(density) { topBarHeight.toDp() },
                end = insetPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            Column(modifier = Modifier.fillMaxHeight()) {
                if (hasSourceTabs) {
                    LibraryMangaGroupTabs(
                        tabs = state.libraryMangaGroupTabs,
                        onSourceTabClicked = onSourceTabClicked,
                        modifier = Modifier.padding(top = topPadding),
                    )
                }
                MangaSourceContent(
                    state = state,
                    modifier = Modifier.weight(1f),
                ) { targetState ->
                    val targetChapters = targetState.processedChapters
                    val targetListItem = targetState.chapterListItems
                    val targetIsAnySelected = targetState.isAnySelected

                    TwoPanelBox(
                        modifier = Modifier.padding(
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            end = contentPadding.calculateEndPadding(layoutDirection),
                        ),
                        startContent = {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = contentPadding.calculateBottomPadding()),
                            ) {
                                MangaInfoBox(
                                    isTabletUi = true,
                                    appBarPadding = if (hasSourceTabs) 0.dp else topPadding,
                                    manga = targetState.manga,
                                    sourceName = remember(targetState.source) {
                                        targetState.source.getNameForMangaInfo()
                                    },
                                    isStubSource = remember(targetState.source) { targetState.source is StubSource },
                                    onCoverClick = onCoverClicked,
                                    doSearch = onSearch,
                                    localVaultImport = targetState.localVaultImport,
                                    onVaultTargetClick = onVaultTargetClicked,
                                )
                                if (!targetState.source.isLocal()) {
                                    MangaActionRow(
                                        favorite = targetState.manga.favorite,
                                        trackingCount = targetState.tracking.count,
                                        nextUpdate = nextUpdate,
                                        isUserIntervalMode = targetState.manga.fetchInterval < 0,
                                        onAddToLibraryClicked = onAddToLibraryClicked,
                                        onWebViewClicked = onWebViewClicked,
                                        onWebViewLongClicked = onWebViewLongClicked,
                                        onTrackingClicked = onTrackingClicked,
                                        onEditIntervalClicked = onEditIntervalClicked,
                                        onEditCategory = onEditCategoryClicked,
                                    )
                                }
                                ExpandableMangaDescription(
                                    defaultExpandState = true,
                                    description = targetState.manga.description,
                                    tagsProvider = { targetState.manga.genre },
                                    notes = targetState.manga.notes,
                                    onTagSearch = onTagSearch,
                                    onCopyTagToClipboard = onCopyTagToClipboard,
                                    onEditNotes = onEditNotesClicked,
                                )
                            }
                        },
                        endContent = {
                            VerticalFastScroller(
                                listState = chapterListState,
                                topContentPadding = if (hasSourceTabs) 0.dp else contentPadding.calculateTopPadding(),
                            ) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxHeight(),
                                    state = chapterListState,
                                    contentPadding = PaddingValues(
                                        top = if (hasSourceTabs) 0.dp else contentPadding.calculateTopPadding(),
                                        bottom = contentPadding.calculateBottomPadding(),
                                    ),
                                ) {
                                    item(
                                        key = MangaScreenItem.CHAPTER_HEADER,
                                        contentType = MangaScreenItem.CHAPTER_HEADER,
                                    ) {
                                        val missingChapterCount = remember(targetChapters) {
                                            targetChapters.map { it.chapter.chapterNumber }.missingChaptersCount()
                                        }
                                        ChapterHeader(
                                            enabled = !targetIsAnySelected,
                                            chapterCount = targetChapters.size,
                                            missingChapterCount = missingChapterCount,
                                            onClick = onFilterButtonClicked,
                                        )
                                    }

                                    sharedChapterItems(
                                        manga = targetState.manga,
                                        chapters = targetListItem,
                                        isAnyChapterSelected = targetChapters.fastAny { it.selected },
                                        chapterSwipeStartAction = chapterSwipeStartAction,
                                        chapterSwipeEndAction = chapterSwipeEndAction,
                                        onChapterClicked = onChapterClicked,
                                        onDownloadChapter = onDownloadChapter,
                                        onChapterSelected = onChapterSelected,
                                        onChapterSwipe = onChapterSwipe,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
