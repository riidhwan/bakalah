package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetSameTitleLibraryManga
import tachiyomi.domain.manga.interactor.ManageLibraryMangaGroup
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

class MangaLibraryActionCoordinatorTest {

    @Test
    fun `add to library returns duplicate result before updating favorite`() = runTest {
        val manga = manga(MANGA_ID, title = "One Piece")
        val duplicate = manga(DUPLICATE_MANGA_ID, title = "One Piece", source = DUPLICATE_SOURCE_ID)
        val fixture = fixture()
        coEvery { fixture.getSameTitleLibraryManga(manga) } returns listOf(
            MangaWithChapterCount(duplicate, DUPLICATE_CHAPTER_COUNT),
        )
        coEvery { fixture.manageLibraryMangaGroup.getGroupForManga(DUPLICATE_MANGA_ID) } returns null

        val result = fixture.coordinator.addToLibrary(
            manga = manga,
            checkDuplicate = true,
        )

        val duplicateResult = result.shouldBeInstanceOf<AddToLibraryResult.DuplicateFound>()
        duplicateResult.duplicates.map { it.manga.id }.shouldContainExactly(DUPLICATE_MANGA_ID)
        duplicateResult.groupTargets.single().memberMangaIds.shouldContainExactly(DUPLICATE_MANGA_ID)
        coVerify(exactly = 0) { fixture.updateManga.awaitUpdateFavorite(any(), any()) }
    }

    @Test
    fun `add to library asks for category selection when default category is unset`() = runTest {
        val manga = manga(MANGA_ID)
        val category = category(CATEGORY_ID)
        val selectedCategory = category(SELECTED_CATEGORY_ID)
        val fixture = fixture()
        coEvery { fixture.getSameTitleLibraryManga(manga) } returns emptyList()
        coEvery { fixture.getCategories.await() } returns listOf(category)
        coEvery { fixture.getCategories.await(MANGA_ID) } returns listOf(selectedCategory)

        val result = fixture.coordinator.addToLibrary(
            manga = manga,
            checkDuplicate = true,
        )

        val categoryResult = result.shouldBeInstanceOf<AddToLibraryResult.NeedsCategorySelection>()
        categoryResult.selection.categories.shouldContainExactly(category)
        categoryResult.selection.selectedCategoryIds.shouldContainExactly(SELECTED_CATEGORY_ID)
        categoryResult.pendingAddToGroup shouldBe null
        coVerify(exactly = 0) { fixture.updateManga.awaitUpdateFavorite(any(), any()) }
    }

    private fun fixture(): Fixture {
        val getSameTitleLibraryManga = mockk<GetSameTitleLibraryManga>()
        val getCategories = mockk<GetCategories>()
        val updateManga = mockk<UpdateManga>()
        val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
        val manageLibraryMangaGroup = mockk<ManageLibraryMangaGroup>()
        return Fixture(
            getSameTitleLibraryManga = getSameTitleLibraryManga,
            getCategories = getCategories,
            updateManga = updateManga,
            coordinator = MangaLibraryActionCoordinator(
                MangaLibraryActionCoordinator.Dependencies(
                    libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
                    getSameTitleLibraryManga = getSameTitleLibraryManga,
                    getCategories = getCategories,
                    updateManga = updateManga,
                    setMangaCategories = setMangaCategories,
                    manageLibraryMangaGroup = manageLibraryMangaGroup,
                    libraryMangaGroupStateBuilder = LibraryMangaGroupStateBuilder(
                        sourceName = { sourceId -> "Source $sourceId" },
                    ),
                ),
            ),
            manageLibraryMangaGroup = manageLibraryMangaGroup,
        )
    }

    private fun manga(
        id: Long,
        title: String = "Manga $id",
        source: Long = SOURCE_ID,
    ): Manga {
        return Manga.create().copy(
            id = id,
            source = source,
            title = title,
        )
    }

    private fun category(id: Long): Category {
        return Category(
            id = id,
            name = "Category $id",
            order = id,
            flags = 0,
        )
    }

    private data class Fixture(
        val getSameTitleLibraryManga: GetSameTitleLibraryManga,
        val getCategories: GetCategories,
        val updateManga: UpdateManga,
        val coordinator: MangaLibraryActionCoordinator,
        val manageLibraryMangaGroup: ManageLibraryMangaGroup,
    )

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 100L
        const val DUPLICATE_MANGA_ID = 2L
        const val DUPLICATE_SOURCE_ID = 200L
        const val DUPLICATE_CHAPTER_COUNT = 12L
        const val CATEGORY_ID = 10L
        const val SELECTED_CATEGORY_ID = 20L
    }
}
