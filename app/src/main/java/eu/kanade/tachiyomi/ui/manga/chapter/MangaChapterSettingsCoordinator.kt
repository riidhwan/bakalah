package eu.kanade.tachiyomi.ui.manga.chapter

import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga

internal class MangaChapterSettingsCoordinator(
    private val dependencies: Dependencies,
) {

    suspend fun setUnreadFilter(manga: Manga, state: TriState) {
        dependencies.setMangaChapterFlags.awaitSetUnreadFilter(
            manga = manga,
            flag = when (state) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
            },
        )
    }

    suspend fun setDownloadedFilter(manga: Manga, state: TriState) {
        dependencies.setMangaChapterFlags.awaitSetDownloadedFilter(
            manga = manga,
            flag = when (state) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
            },
        )
    }

    suspend fun setBookmarkedFilter(manga: Manga, state: TriState) {
        dependencies.setMangaChapterFlags.awaitSetBookmarkFilter(
            manga = manga,
            flag = when (state) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
            },
        )
    }

    suspend fun setDisplayMode(manga: Manga, mode: Long) {
        dependencies.setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
    }

    suspend fun setSorting(manga: Manga, sort: Long) {
        dependencies.setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
    }

    suspend fun setCurrentSettingsAsDefault(manga: Manga, applyToExisting: Boolean) {
        dependencies.libraryPreferences.setChapterSettingsDefault(manga)
        if (applyToExisting) {
            dependencies.setMangaDefaultChapterFlags.awaitAll()
        }
    }

    suspend fun resetToDefaultSettings(manga: Manga) {
        dependencies.setMangaDefaultChapterFlags.await(manga)
    }

    data class Dependencies(
        val libraryPreferences: LibraryPreferences,
        val setMangaChapterFlags: SetMangaChapterFlags,
        val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    )
}
