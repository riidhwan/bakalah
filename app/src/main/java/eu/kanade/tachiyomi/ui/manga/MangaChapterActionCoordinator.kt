package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

internal class MangaChapterActionCoordinator(
    private val runtime: Runtime,
    private val dependencies: Dependencies,
    private val coordinators: Coordinators,
    private val callbacks: Callbacks,
) {

    fun chapterSwipe(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        runtime.screenModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    fun getNextUnreadChapter(): Chapter? {
        val state = callbacks.getState() ?: return null
        return state.chapters.getNextUnread(state.manga)
    }

    fun downloadChapters(chapters: List<Chapter>) {
        val manga = callbacks.getState()?.manga ?: return
        coordinators.downloadCoordinator.downloadChapters(manga, chapters)
        callbacks.toggleAllSelection(false)
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    coordinators.downloadCoordinator.startDownloads()
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

    fun runDownloadAction(
        action: DownloadAction,
        manga: Manga,
        allChapters: List<ChapterList.Item>,
        filteredChapters: List<ChapterList.Item>,
    ) {
        val chaptersToDownload = coordinators.downloadCoordinator.chaptersForDownloadAction(
            action = action,
            manga = manga,
            allChapters = allChapters,
            filteredChapters = filteredChapters,
            skipFiltered = dependencies.skipFiltered(),
        )
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    fun markPreviousChapterRead(
        pointer: Chapter,
        manga: Manga,
        filteredChapters: List<ChapterList.Item>,
    ) {
        val chapters = filteredChapters.map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        callbacks.toggleAllSelection(false)
        if (chapters.isEmpty()) return

        runtime.screenModelScope.launchIO {
            dependencies.setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            if (!read) {
                return@launchIO
            }

            val state = callbacks.getState() ?: return@launchIO
            val result = coordinators.trackingCoordinator.planMarkReadTrackingUpdate(
                mangaId = state.manga.id,
                chapters = chapters,
                hasLoggedInTrackers = state.tracking.hasLoggedInTrackers,
                autoTrackState = dependencies.autoTrackState(),
            )
            showTrackerRefreshFailures(result.refreshFailures)
            handleTrackingUpdate(result.update)
        }
    }

    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        runtime.screenModelScope.launchIO {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { dependencies.updateChapter.awaitAll(it) }
        }
        callbacks.toggleAllSelection(false)
    }

    fun deleteChapters(chapters: List<Chapter>) {
        runtime.screenModelScope.launchNonCancellable {
            try {
                callbacks.getState()?.let { state ->
                    coordinators.downloadCoordinator.deleteChapters(chapters, state.manga, state.source)
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

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
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = coordinators.downloadCoordinator.swipeActionFor(chapterItem.downloadState),
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val state = callbacks.getState() ?: return

        runtime.screenModelScope.launchNonCancellable {
            if (startNow) {
                val chapterId = chapters.singleOrNull()?.id ?: return@launchNonCancellable
                coordinators.downloadCoordinator.startDownloads(chapterId)
            } else {
                downloadChapters(chapters)
            }

            if (callbacks.getState()?.manga?.favorite != true && !state.hasPromptedToAddBefore) {
                callbacks.updateState { it.copy(hasPromptedToAddBefore = true) }
                val result = runtime.snackbarHostState.showSnackbar(
                    message = runtime.context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = runtime.context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && callbacks.getState()?.manga?.favorite != true) {
                    callbacks.toggleFavorite()
                }
            }
        }
    }

    private fun cancelDownload(chapterId: Long) {
        callbacks.updateDownloadState(coordinators.downloadCoordinator.cancelDownload(chapterId) ?: return)
    }

    private suspend fun showTrackerRefreshFailures(failures: List<MangaTrackerRefreshFailure>) {
        failures.forEach { failure ->
            withUIContext {
                runtime.context.toast(
                    runtime.context.stringResource(
                        MR.strings.track_error,
                        failure.trackerName,
                        failure.error.message ?: "",
                    ),
                )
            }
        }
    }

    private suspend fun handleTrackingUpdate(update: MangaTrackingUpdate?) {
        when (update) {
            null -> Unit
            is MangaTrackingUpdate.Auto -> {
                coordinators.trackingCoordinator.trackChapter(runtime.context, update)
                withUIContext {
                    runtime.context.toast(
                        runtime.context.stringResource(
                            MR.strings.trackers_updated_summary,
                            update.chapterNumber.toInt(),
                        ),
                    )
                }
            }
            is MangaTrackingUpdate.Prompt -> {
                val result = runtime.snackbarHostState.showSnackbar(
                    message = runtime.context.stringResource(
                        MR.strings.confirm_tracker_update,
                        update.chapterNumber.toInt(),
                    ),
                    actionLabel = runtime.context.stringResource(MR.strings.action_ok),
                    duration = SnackbarDuration.Short,
                    withDismissAction = true,
                )

                if (result == SnackbarResult.ActionPerformed) {
                    coordinators.trackingCoordinator.trackChapter(runtime.context, update)
                }
            }
        }
    }

    data class Runtime(
        val context: Context,
        val screenModelScope: CoroutineScope,
        val snackbarHostState: SnackbarHostState,
    )

    data class Dependencies(
        val setReadStatus: SetReadStatus,
        val updateChapter: UpdateChapter,
        val skipFiltered: () -> Boolean,
        val autoTrackState: () -> AutoTrackState,
    )

    data class Coordinators(
        val downloadCoordinator: MangaDownloadCoordinator,
        val trackingCoordinator: MangaTrackingCoordinator,
    )

    data class Callbacks(
        val getState: () -> MangaScreenModel.State.Success?,
        val updateState: ((MangaScreenModel.State.Success) -> MangaScreenModel.State.Success) -> Unit,
        val updateDownloadState: (Download) -> Unit,
        val toggleAllSelection: (Boolean) -> Unit,
        val toggleFavorite: () -> Unit,
    )
}
