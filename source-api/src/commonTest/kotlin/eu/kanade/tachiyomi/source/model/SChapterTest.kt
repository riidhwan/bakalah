package eu.kanade.tachiyomi.source.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class SChapterTest {

    @Test
    fun `memo setter is available for tachiyomix 1_6 extensions`() {
        val memo = buildJsonObject {
            put(MEMO_KEY, MEMO_VALUE)
        }

        val chapter = SChapter.create()
        chapter.memo = memo

        chapter.memo shouldBe memo
        SChapter::class.java.methods.find { method ->
            method.name == "setMemo" && method.parameterTypes.singleOrNull()?.name == JSON_OBJECT_CLASS
        } shouldNotBe null
    }

    @Test
    fun `copyFrom preserves memo`() {
        val memo = buildJsonObject {
            put(MEMO_KEY, MEMO_VALUE)
        }
        val source = SChapter.create().also {
            it.name = "Chapter 1"
            it.url = "/chapter-1"
            it.memo = memo
        }
        val target = SChapter.create()

        target.copyFrom(source)

        target.memo shouldBe memo
    }

    private companion object {
        const val MEMO_KEY = "mihon.test"
        const val MEMO_VALUE = "value"
        const val JSON_OBJECT_CLASS = "kotlinx.serialization.json.JsonObject"
    }
}
