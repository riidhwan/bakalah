package eu.kanade.tachiyomi.ui.manga.source

import android.content.Context
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.manga.effect.MangaUiEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

internal class MangaSourceRefreshUiCoordinator(
    private val context: Context,
    private val runtime: Runtime,
    private val sourceRefreshCoordinator: MangaSourceRefreshCoordinator,
    private val callbacks: Callbacks,
) {

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        runtime.screenModelScope.launch {
            val state = callbacks.getState() ?: return@launch
            callbacks.updateState { it.copy(isRefreshingData = true) }
            try {
                refresh(
                    manga = state.manga,
                    source = state.source,
                    refreshManga = true,
                    refreshChapters = true,
                    manualFetch = manualFetch,
                )
            } finally {
                callbacks.updateState { it.copy(isRefreshingData = false) }
            }
        }
    }

    suspend fun refreshOnLoad(snapshot: MangaLoadSnapshot) {
        try {
            refresh(
                manga = snapshot.manga,
                source = snapshot.source,
                refreshManga = snapshot.needRefreshInfo,
                refreshChapters = snapshot.needRefreshChapter,
                manualFetch = false,
            )
        } finally {
            callbacks.updateState { it.copy(isRefreshingData = false) }
        }
    }

    private suspend fun refresh(
        manga: Manga,
        source: Source,
        refreshManga: Boolean,
        refreshChapters: Boolean,
        manualFetch: Boolean,
    ) {
        applyRefreshOutcome(
            sourceRefreshCoordinator.refreshFromSource(
                manga = manga,
                source = source,
                refreshManga = refreshManga,
                refreshChapters = refreshChapters,
                manualFetch = manualFetch,
            ),
        )
    }

    private fun applyRefreshOutcome(outcome: MangaSourceRefreshOutcome) {
        when (val result = outcome.mangaDetails) {
            null,
            MangaSourceRefreshResult.Success,
            MangaSourceRefreshResult.IgnoredEarlyHints,
            -> Unit
            is MangaSourceRefreshResult.Failed -> {
                showSourceRefreshError(result.error)
            }
        }

        when (val result = outcome.chapters) {
            null -> Unit
            is ChapterSourceRefreshResult.Success -> {
                if (result.chaptersToDownload.isNotEmpty()) {
                    callbacks.downloadChapters(result.chaptersToDownload)
                }
            }
            is ChapterSourceRefreshResult.NoChapters -> {
                showSourceRefreshMessage(context.stringResource(MR.strings.no_chapters_error))
                callbacks.updateState { it.copy(manga = result.latestManga, isRefreshingData = false) }
            }
            is ChapterSourceRefreshResult.Failed -> {
                showSourceRefreshError(result.error)
                callbacks.updateState { it.copy(manga = result.latestManga, isRefreshingData = false) }
            }
        }
    }

    private fun showSourceRefreshError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        showSourceRefreshMessage(with(context) { error.formattedMessage })
    }

    private fun showSourceRefreshMessage(message: String) {
        callbacks.showUiEffect(MangaUiEffect.ShowSnackbar(message = message))
    }

    data class Runtime(
        val screenModelScope: CoroutineScope,
    )

    data class Callbacks(
        val getState: () -> MangaScreenModel.State.Success?,
        val updateState: ((MangaScreenModel.State.Success) -> MangaScreenModel.State.Success) -> Unit,
        val downloadChapters: (List<Chapter>) -> Unit,
        val showUiEffect: (MangaUiEffect) -> Unit,
    )
}
