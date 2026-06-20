package tachiyomi.domain.history.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistorySourceFilter
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class GetHistoryTest {

    private val repository = RecordingHistoryRepository(
        history = listOf(
            history(
                id = 1,
                title = "Remote favorite",
                sourceId = REMOTE_SOURCE_ID,
                favorite = true,
            ),
            history(
                id = 2,
                title = "Local entry",
                sourceId = LOCAL_SOURCE_ID,
                favorite = false,
            ),
            history(
                id = 3,
                title = "Remote non-library",
                sourceId = REMOTE_SOURCE_ID,
                favorite = false,
            ),
        ),
    )
    private val getHistory = GetHistory(repository)

    @Test
    fun `library history requests favorite non-local scope`() = runTest {
        val filter = HistorySourceFilter.Library(excludedLocalSourceId = LOCAL_SOURCE_ID)

        val result = getHistory.subscribe(query = "", sourceFilter = filter).single()

        repository.lastHistoryRequest shouldBe HistoryRequest(query = "", sourceFilter = filter)
        result.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `local history requests local source scope independent of favorite`() = runTest {
        val filter = HistorySourceFilter.Local(localSourceId = LOCAL_SOURCE_ID)

        val result = getHistory.subscribe(query = "", sourceFilter = filter).single()

        repository.lastHistoryRequest shouldBe HistoryRequest(query = "", sourceFilter = filter)
        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `source history requests non-library non-local scope`() = runTest {
        val filter = HistorySourceFilter.Source(excludedLocalSourceId = LOCAL_SOURCE_ID)

        val result = getHistory.subscribe(query = "", sourceFilter = filter).single()

        repository.lastHistoryRequest shouldBe HistoryRequest(query = "", sourceFilter = filter)
        result.map { it.id } shouldBe listOf(3L)
    }

    @Test
    fun `search query filters within selected scope`() = runTest {
        val result = getHistory.subscribe(
            query = "favorite",
            sourceFilter = HistorySourceFilter.Library(excludedLocalSourceId = LOCAL_SOURCE_ID),
        ).single()

        result.map { it.title } shouldBe listOf("Remote favorite")
    }

    @Test
    fun `search query does not return matches outside selected scope`() = runTest {
        val result = getHistory.subscribe(
            query = "local",
            sourceFilter = HistorySourceFilter.Library(excludedLocalSourceId = LOCAL_SOURCE_ID),
        ).single()

        result shouldBe emptyList()
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
        const val REMOTE_SOURCE_ID = 1L
    }
}

class RemoveHistoryTest {

    private val repository = RecordingHistoryRepository()
    private val removeHistory = RemoveHistory(repository)

    @Test
    fun `clear all library history uses library scope`() = runTest {
        val filter = HistorySourceFilter.Library(excludedLocalSourceId = LOCAL_SOURCE_ID)

        removeHistory.awaitAll(filter) shouldBe true

        repository.lastDeleteAllFilter shouldBe filter
    }

    @Test
    fun `clear all local history uses local scope`() = runTest {
        val filter = HistorySourceFilter.Local(localSourceId = LOCAL_SOURCE_ID)

        removeHistory.awaitAll(filter) shouldBe true

        repository.lastDeleteAllFilter shouldBe filter
    }

    @Test
    fun `clear all source history uses source scope`() = runTest {
        val filter = HistorySourceFilter.Source(excludedLocalSourceId = LOCAL_SOURCE_ID)

        removeHistory.awaitAll(filter) shouldBe true

        repository.lastDeleteAllFilter shouldBe filter
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
    }
}

private data class HistoryRequest(
    val query: String,
    val sourceFilter: HistorySourceFilter,
)

private class RecordingHistoryRepository(
    private val history: List<HistoryWithRelations> = emptyList(),
) : HistoryRepository {

    var lastHistoryRequest: HistoryRequest? = null
        private set

    var lastDeleteAllFilter: HistorySourceFilter? = null
        private set

    override fun getHistory(
        query: String,
        sourceFilter: HistorySourceFilter,
    ): Flow<List<HistoryWithRelations>> {
        lastHistoryRequest = HistoryRequest(query, sourceFilter)
        return flowOf(
            history
                .filter { sourceFilter.includes(it.coverData.sourceId, it.coverData.isMangaFavorite) }
                .filter { it.title.contains(query, ignoreCase = true) },
        )
    }

    override suspend fun getLastHistory(): HistoryWithRelations? = null

    override suspend fun getTotalReadDuration(): Long = 0

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> = emptyList()

    override suspend fun resetHistory(historyId: Long) = Unit

    override suspend fun resetHistoryByMangaId(mangaId: Long) = Unit

    override suspend fun deleteAllHistory(sourceFilter: HistorySourceFilter): Boolean {
        lastDeleteAllFilter = sourceFilter
        return true
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) = Unit
}

private fun HistorySourceFilter.includes(sourceId: Long, favorite: Boolean): Boolean {
    return when (this) {
        is HistorySourceFilter.Library -> favorite && sourceId != excludedLocalSourceId
        is HistorySourceFilter.Local -> sourceId == localSourceId
        is HistorySourceFilter.Source -> !favorite && sourceId != excludedLocalSourceId
    }
}

private fun history(
    id: Long,
    title: String,
    sourceId: Long,
    favorite: Boolean,
) = HistoryWithRelations(
    id = id,
    chapterId = id,
    mangaId = id,
    title = title,
    chapterNumber = id.toDouble(),
    readAt = Date(id),
    readDuration = id,
    coverData = MangaCover(
        mangaId = id,
        sourceId = sourceId,
        isMangaFavorite = favorite,
        url = null,
        lastModified = 0,
    ),
)
