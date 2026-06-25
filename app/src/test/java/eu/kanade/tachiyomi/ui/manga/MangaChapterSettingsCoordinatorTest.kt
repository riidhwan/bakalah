package eu.kanade.tachiyomi.ui.manga

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga

class MangaChapterSettingsCoordinatorTest {

    @Test
    fun `filter changes map tri-state values to manga chapter flags`() = runTest {
        val fixture = fixture()
        val manga = manga()
        coEvery { fixture.setMangaChapterFlags.awaitSetUnreadFilter(manga, any()) } returns true
        coEvery { fixture.setMangaChapterFlags.awaitSetDownloadedFilter(manga, any()) } returns true
        coEvery { fixture.setMangaChapterFlags.awaitSetBookmarkFilter(manga, any()) } returns true

        fixture.coordinator.setUnreadFilter(manga, TriState.ENABLED_IS)
        fixture.coordinator.setDownloadedFilter(manga, TriState.ENABLED_NOT)
        fixture.coordinator.setBookmarkedFilter(manga, TriState.DISABLED)

        coVerify {
            fixture.setMangaChapterFlags.awaitSetUnreadFilter(manga, Manga.CHAPTER_SHOW_UNREAD)
            fixture.setMangaChapterFlags.awaitSetDownloadedFilter(manga, Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
            fixture.setMangaChapterFlags.awaitSetBookmarkFilter(manga, Manga.SHOW_ALL)
        }
    }

    @Test
    fun `display and sorting changes delegate selected flags`() = runTest {
        val fixture = fixture()
        val manga = manga()
        coEvery { fixture.setMangaChapterFlags.awaitSetDisplayMode(manga, any()) } returns true
        coEvery { fixture.setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, any()) } returns true

        fixture.coordinator.setDisplayMode(manga, Manga.CHAPTER_DISPLAY_NUMBER)
        fixture.coordinator.setSorting(manga, Manga.CHAPTER_SORTING_UPLOAD_DATE)

        coVerify {
            fixture.setMangaChapterFlags.awaitSetDisplayMode(manga, Manga.CHAPTER_DISPLAY_NUMBER)
            fixture.setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, Manga.CHAPTER_SORTING_UPLOAD_DATE)
        }
    }

    @Test
    fun `setting current settings as default optionally applies to existing manga`() = runTest {
        val fixture = fixture()
        val manga = manga()
        coEvery { fixture.setMangaDefaultChapterFlags.awaitAll() } returns Unit

        fixture.coordinator.setCurrentSettingsAsDefault(manga, applyToExisting = true)

        coVerify { fixture.setMangaDefaultChapterFlags.awaitAll() }
    }

    @Test
    fun `reset to default settings applies defaults to current manga`() = runTest {
        val fixture = fixture()
        val manga = manga()
        coEvery { fixture.setMangaDefaultChapterFlags.await(manga) } returns Unit

        fixture.coordinator.resetToDefaultSettings(manga)

        coVerify { fixture.setMangaDefaultChapterFlags.await(manga) }
    }

    private fun fixture(): Fixture {
        val setMangaChapterFlags = mockk<SetMangaChapterFlags>()
        val setMangaDefaultChapterFlags = mockk<SetMangaDefaultChapterFlags>()
        return Fixture(
            setMangaChapterFlags = setMangaChapterFlags,
            setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
            coordinator = MangaChapterSettingsCoordinator(
                MangaChapterSettingsCoordinator.Dependencies(
                    libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
                    setMangaChapterFlags = setMangaChapterFlags,
                    setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
                ),
            ),
        )
    }

    private fun manga(): Manga {
        return Manga.create().copy(id = MANGA_ID)
    }

    private data class Fixture(
        val setMangaChapterFlags: SetMangaChapterFlags,
        val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
        val coordinator: MangaChapterSettingsCoordinator,
    )

    private companion object {
        const val MANGA_ID = 1L
    }
}
