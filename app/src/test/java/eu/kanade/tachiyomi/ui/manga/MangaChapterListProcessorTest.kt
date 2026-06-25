package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.chapter.MangaChapterListProcessor
import eu.kanade.tachiyomi.ui.manga.model.ChapterList
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class MangaChapterListProcessorTest {

    @Test
    fun `missing chapter separators are inserted between chapter gaps`() {
        val chapters = chapterItems(CHAPTER_THREE, CHAPTER_ONE)

        val result = MangaChapterListProcessor.withMissingChapterSeparators(
            processedChapters = chapters,
            manga = manga(sortDescending = true),
            hideMissingChapters = false,
        )

        result.map { item ->
            when (item) {
                is ChapterList.Item -> "chapter:${item.chapter.chapterNumber.toInt()}"
                is ChapterList.MissingCount -> "missing:${item.count}"
            }
        }.shouldContainExactly("chapter:3", "missing:1", "chapter:1")
    }

    @Test
    fun `missing chapter separators are skipped when hidden`() {
        val chapters = chapterItems(CHAPTER_THREE, CHAPTER_ONE)

        val result = MangaChapterListProcessor.withMissingChapterSeparators(
            processedChapters = chapters,
            manga = manga(sortDescending = true),
            hideMissingChapters = true,
        )

        result shouldBe chapters
    }

    private fun manga(sortDescending: Boolean): Manga {
        val sortDirection = if (sortDescending) {
            Manga.CHAPTER_SORT_DESC
        } else {
            Manga.CHAPTER_SORT_ASC
        }
        return Manga.create().copy(chapterFlags = sortDirection)
    }

    private fun chapterItems(vararg chapterNumbers: Double): List<ChapterList.Item> {
        return chapterNumbers.mapIndexed { index, chapterNumber ->
            ChapterList.Item(
                chapter = Chapter.create().copy(
                    id = index.toLong() + 1,
                    url = "chapter-$chapterNumber",
                    name = "Chapter $chapterNumber",
                    chapterNumber = chapterNumber,
                ),
                downloadState = Download.State.NOT_DOWNLOADED,
                downloadProgress = 0,
            )
        }
    }

    private companion object {
        const val CHAPTER_ONE = 1.0
        const val CHAPTER_THREE = 3.0
    }
}
