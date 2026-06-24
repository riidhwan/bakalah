package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository

@Execution(ExecutionMode.CONCURRENT)
class GetSameTitleLibraryMangaTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val getSameTitleLibraryManga = GetSameTitleLibraryManga(mangaRepository)

    @Test
    fun `returns library manga with the same normalized title key`() = runTest {
        val manga = manga(id = 1, title = "Sousou no Frieren")
        val matchingLibraryManga = manga(id = 2, title = "Sousou no Frieren", favorite = true)
        val punctuationVariant = manga(id = 3, title = "Sousou: no Frieren!", favorite = true)

        coEvery { mangaRepository.getLibraryMangaWithChapterCount() } returns listOf(
            mangaWithChapterCount(matchingLibraryManga),
            mangaWithChapterCount(punctuationVariant),
        )

        getSameTitleLibraryManga(manga).map { it.manga.id } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `does not return substring-only title matches`() = runTest {
        val manga = manga(id = 1, title = "One Piece")
        val substringOnlyMatch = manga(id = 2, title = "One Piece Colored", favorite = true)

        coEvery { mangaRepository.getLibraryMangaWithChapterCount() } returns listOf(
            mangaWithChapterCount(substringOnlyMatch),
        )

        getSameTitleLibraryManga(manga) shouldBe emptyList()
    }

    @Test
    fun `does not check local source or already favorited manga`() = runTest {
        coEvery { mangaRepository.getLibraryMangaWithChapterCount() } returns listOf(
            mangaWithChapterCount(manga(id = 2, title = "One Piece", favorite = true)),
        )

        getSameTitleLibraryManga(manga(id = 1, title = "One Piece", source = LOCAL_SOURCE_ID)) shouldBe emptyList()
        getSameTitleLibraryManga(manga(id = 1, title = "One Piece", favorite = true)) shouldBe emptyList()
    }

    private fun manga(
        id: Long,
        title: String,
        source: Long = 1L,
        favorite: Boolean = false,
    ): Manga {
        return Manga.create().copy(
            id = id,
            title = title,
            source = source,
            favorite = favorite,
        )
    }

    private fun mangaWithChapterCount(manga: Manga): MangaWithChapterCount {
        return MangaWithChapterCount(
            manga = manga,
            chapterCount = 1,
        )
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
    }
}
