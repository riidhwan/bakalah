package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal

internal class MangaDownloadCoordinator(
    private val downloadManager: DownloadManager,
) {

    fun observeDownloadUpdates(activeMangaId: () -> Long?): Flow<Download> {
        return merge(
            downloadManager.statusFlow(),
            downloadManager.progressFlow(),
        )
            .filter { download -> download.manga.id == activeMangaId() }
            .catch { error -> logcat(LogPriority.ERROR, error) }
    }

    fun chapterListItems(
        chapters: List<Chapter>,
        manga: Manga,
        isSelected: (Long) -> Boolean,
        duplicateSelectionIds: Set<String>,
    ): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        return chapters.map { chapter ->
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
                selected = isSelected(chapter.id),
                importDuplicate = chapter.url in duplicateSelectionIds,
            )
        }
    }

    fun updateDownloadState(
        chapters: List<ChapterList.Item>,
        download: Download,
    ): List<ChapterList.Item> {
        val modifiedIndex = chapters.indexOfFirst { it.id == download.chapter.id }
        if (modifiedIndex < 0) return chapters

        return chapters.toMutableList().apply {
            val item = removeAt(modifiedIndex)
                .copy(downloadState = download.status, downloadProgress = download.progress)
            add(modifiedIndex, item)
        }
    }

    fun swipeActionFor(downloadState: Download.State): ChapterDownloadAction {
        return when (downloadState) {
            Download.State.ERROR,
            Download.State.NOT_DOWNLOADED,
            -> ChapterDownloadAction.START_NOW
            Download.State.QUEUE,
            Download.State.DOWNLOADING,
            -> ChapterDownloadAction.CANCEL
            Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
        }
    }

    fun chaptersForDownloadAction(
        action: DownloadAction,
        manga: Manga,
        allChapters: List<ChapterList.Item>,
        filteredChapters: List<ChapterList.Item>,
        skipFiltered: Boolean,
    ): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters else allChapters
        return when (action) {
            DownloadAction.NEXT_1_CHAPTER -> unreadChaptersSorted(chapterItems, manga).take(1)
            DownloadAction.NEXT_5_CHAPTERS -> unreadChaptersSorted(chapterItems, manga).take(NEXT_5_CHAPTERS)
            DownloadAction.NEXT_10_CHAPTERS -> unreadChaptersSorted(chapterItems, manga).take(NEXT_10_CHAPTERS)
            DownloadAction.NEXT_25_CHAPTERS -> unreadChaptersSorted(chapterItems, manga).take(NEXT_25_CHAPTERS)
            DownloadAction.UNREAD_CHAPTERS -> unreadChapters(chapterItems)
            DownloadAction.BOOKMARKED_CHAPTERS -> bookmarkedChapters(chapterItems)
        }
    }

    fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        downloadManager.downloadChapters(manga, chapters)
    }

    fun hasDownloads(manga: Manga): Boolean {
        return downloadManager.getDownloadCount(manga) > 0
    }

    fun deleteMangaDownloads(manga: Manga, source: Source) {
        downloadManager.deleteManga(manga, source)
    }

    fun startDownloads(chapterId: Long? = null) {
        if (chapterId != null) {
            downloadManager.startDownloadNow(chapterId)
        } else {
            downloadManager.startDownloads()
        }
    }

    fun cancelDownload(chapterId: Long): Download? {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return null
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        activeDownload.status = Download.State.NOT_DOWNLOADED
        return activeDownload
    }

    fun deleteChapters(chapters: List<Chapter>, manga: Manga, source: Source) {
        downloadManager.deleteChapters(chapters, manga, source)
    }

    private companion object {
        const val NEXT_5_CHAPTERS = 5
        const val NEXT_10_CHAPTERS = 10
        const val NEXT_25_CHAPTERS = 25
    }
}

private fun unreadChapters(chapterItems: List<ChapterList.Item>): List<Chapter> {
    return chapterItems
        .filter { (chapter, downloadState) -> !chapter.read && downloadState == Download.State.NOT_DOWNLOADED }
        .map { it.chapter }
}

private fun unreadChaptersSorted(
    chapterItems: List<ChapterList.Item>,
    manga: Manga,
): List<Chapter> {
    val chaptersSorted = unreadChapters(chapterItems).sortedWith(getChapterSort(manga))
    return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
}

private fun bookmarkedChapters(chapterItems: List<ChapterList.Item>): List<Chapter> {
    return chapterItems
        .filter { (chapter, downloadState) -> chapter.bookmark && downloadState == Download.State.NOT_DOWNLOADED }
        .map { it.chapter }
}
