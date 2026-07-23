package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class ReaderChapterMetadataRefreshTest {

    @Test
    fun `finds matching refreshed chapter by url`() {
        val staleChapter = chapter(url = "/chapter-1", memoValue = "old")
        val refreshedChapter = chapter(url = "/chapter-1", memoValue = "new")

        val match = listOf(
            chapter(url = "/chapter-2", memoValue = "other"),
            refreshedChapter,
        ).findReaderChapterMatch(staleChapter)

        match shouldBe refreshedChapter
        match?.memo shouldNotBe staleChapter.memo
    }

    @Test
    fun `does not use metadata from a different chapter`() {
        val staleChapter = chapter(url = "/chapter-1", memoValue = "old")

        listOf(chapter(url = "/chapter-2", memoValue = "new"))
            .findReaderChapterMatch(staleChapter) shouldBe null
    }

    private fun chapter(url: String, memoValue: String): SChapter {
        return SChapter.create().also {
            it.url = url
            it.name = url
            it.memo = buildJsonObject {
                put("memo", memoValue)
            }
        }
    }
}
