package eu.kanade.tachiyomi.source.model

import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import org.junit.jupiter.api.Test

class SMangaTest {

    @Test
    fun `memo setter is available for tachiyomix 1_6 extensions`() {
        val memo = buildJsonObject {
            put(MEMO_KEY, MEMO_VALUE)
        }

        val manga = SManga.create()
        manga.memo = memo

        manga.memo shouldBe memo
        SManga::class.java.methods.find { method ->
            method.name == "setMemo" && method.parameterTypes.singleOrNull()?.name == JSON_OBJECT_CLASS
        } shouldNotBe null
    }

    @Test
    fun `copy preserves memo`() {
        val memo = buildJsonObject {
            put(MEMO_KEY, MEMO_VALUE)
        }
        val manga = SManga.create().also {
            it.url = "/series"
            it.title = "Series"
            it.memo = memo
        }

        val copy = manga.copy()

        copy.memo shouldBe memo
    }

    @Test
    fun `http source manga details uses combined tachiyomix 1_6 update`() = runTest {
        val source = CombinedUpdateHttpSource()
        val manga = SManga.create().also {
            it.url = "/series"
            it.title = "Series"
        }

        val result = source.getMangaDetails(manga)

        result.title shouldBe UPDATED_TITLE
        result.initialized shouldBe true
    }

    @Test
    fun `http source chapter list uses combined tachiyomix 1_6 update`() = runTest {
        val source = CombinedUpdateHttpSource()
        val manga = SManga.create().also {
            it.url = "/series"
            it.title = "Series"
        }

        val result = source.getChapterList(manga)

        result.map { it.name } shouldBe listOf(CHAPTER_NAME)
    }

    @Test
    fun `http source compatibility calls do not enter combined update concurrently`() = runTest {
        val source = CombinedUpdateHttpSource()
        val manga = SManga.create().also {
            it.url = "/series"
            it.title = "Series"
        }

        awaitAll(
            async { source.getMangaDetails(manga) },
            async { source.getChapterList(manga) },
        )

        source.concurrentCallDetected shouldBe false
    }

    private companion object {
        const val MEMO_KEY = "mihon.test"
        const val MEMO_VALUE = "value"
        const val JSON_OBJECT_CLASS = "kotlinx.serialization.json.JsonObject"
        const val UPDATED_TITLE = "Updated series"
        const val CHAPTER_NAME = "Chapter 1"
    }

    private class CombinedUpdateHttpSource : HttpSource() {

        override val baseUrl = "https://example.com"
        override val lang = "en"
        override val name = "Example"
        override val supportsLatest = false

        var concurrentCallDetected = false

        private var updateIsActive = false

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate {
            if (updateIsActive) {
                concurrentCallDetected = true
            }
            updateIsActive = true
            return try {
                delay(1)
                SMangaUpdate(
                    manga = if (fetchDetails) {
                        manga.copy().also {
                            it.title = UPDATED_TITLE
                            it.initialized = true
                        }
                    } else {
                        manga
                    },
                    chapters = if (fetchChapters) {
                        listOf(
                            SChapter.create().also {
                                it.url = "/series/chapter-1"
                                it.name = CHAPTER_NAME
                            },
                        )
                    } else {
                        chapters
                    },
                )
            } finally {
                updateIsActive = false
            }
        }

        override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()

        override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()

        override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
            throw UnsupportedOperationException()

        override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()

        override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

        override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

        override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()

        override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

        override fun pageListParse(response: Response) = throw UnsupportedOperationException()

        override fun chapterPageParse(response: Response) = throw UnsupportedOperationException()

        override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
    }
}
