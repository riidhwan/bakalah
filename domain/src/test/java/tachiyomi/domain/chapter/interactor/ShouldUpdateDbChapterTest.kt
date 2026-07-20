package tachiyomi.domain.chapter.interactor

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ShouldUpdateDbChapterTest {

    private val shouldUpdateDbChapter = ShouldUpdateDbChapter()

    @Test
    fun `memo changes require chapter update`() {
        val dbChapter = chapter(memoValue = "old")
        val sourceChapter = chapter(memoValue = "new")

        shouldUpdateDbChapter.await(dbChapter, sourceChapter) shouldBe true
    }

    @Test
    fun `same memo does not require chapter update`() {
        val dbChapter = chapter(memoValue = "same")
        val sourceChapter = chapter(memoValue = "same")

        shouldUpdateDbChapter.await(dbChapter, sourceChapter) shouldBe false
    }

    private fun chapter(memoValue: String): Chapter {
        return Chapter.create().copy(
            id = 1,
            mangaId = 1,
            url = "/chapter-1",
            name = "Chapter 1",
            memo = buildJsonObject {
                put("key", memoValue)
            },
        )
    }
}
