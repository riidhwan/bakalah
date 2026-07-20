package eu.kanade.domain.chapter.model

import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterMapperTest {

    @Test
    fun `toSChapter preserves memo`() {
        val memo = memo()
        val chapter = Chapter.create().copy(memo = memo)

        chapter.toSChapter().memo shouldBe memo
    }

    @Test
    fun `copyFromSChapter preserves memo`() {
        val memo = memo()
        val sChapter = Chapter.create().toSChapter().also {
            it.memo = memo
        }

        Chapter.create().copyFromSChapter(sChapter).memo shouldBe memo
    }

    @Test
    fun `database chapter round trip preserves memo`() {
        val memo = memo()
        val chapter = Chapter.create().copy(
            id = 1,
            mangaId = 2,
            memo = memo,
        )

        chapter.toDbChapter().toDomainChapter()?.memo shouldBe memo
    }

    private fun memo() = buildJsonObject {
        put("chapter", "memo")
    }
}
