package eu.kanade.tachiyomi.ui.vault

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultImportRequestChapterState
import tachiyomi.domain.vault.model.VaultImportRequestSummary
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.domain.vault.repository.VaultRepository

class ImportRequestsScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `list model exposes empty state`() = runBlocking {
        val model = ImportRequestsScreenModel(repository(summaries = emptyList()))

        val state = model.awaitState { !it.isLoading }

        state.requests shouldBe emptyList()
        state.isError shouldBe false
    }

    @Test
    fun `list model exposes request summaries`() = runBlocking {
        val request = summary(
            id = 1,
            totalChapters = 4,
            pendingChapters = 1,
            completedChapters = 2,
            failedChapters = 1,
            replacedChapters = 1,
        )
        val model = ImportRequestsScreenModel(repository(summaries = listOf(request)))

        val state = model.awaitState { it.requests.isNotEmpty() }

        state.requests.single() shouldBe request
        state.requests.single().sourceMangaTitle shouldBe "Source Manga"
        state.requests.single().sourceMangaSourceId shouldBe 99
        state.requests.single().sourceMangaThumbnailUrl shouldBe "https://example.invalid/cover.jpg"
        state.requests.single().targetMangaTitle shouldBe "Target Manga"
    }

    @Test
    fun `detail model exposes not found state`() = runBlocking {
        val model = ImportRequestChaptersScreenModel(
            requestId = 9,
            repository = repository(request = null),
        )

        val state = model.awaitState { !it.isLoading }

        state.request shouldBe null
        state.isError shouldBe false
    }

    @Test
    fun `detail model preserves repository chapter order`() = runBlocking {
        val request = request(
            chapters = listOf(
                chapter(selectionId = "a", sortOrder = 1),
                chapter(selectionId = "b", sortOrder = 1),
                chapter(selectionId = "c", sortOrder = 2),
            ),
        )
        val model = ImportRequestChaptersScreenModel(
            requestId = request.id,
            repository = repository(request = request),
        )

        val state = model.awaitState { it.request != null }

        state.request?.chapters?.map { it.selectionId } shouldContainExactly listOf("a", "b", "c")
        state.request?.chapters?.map { it.chapterTitle } shouldContainExactly
            listOf("Chapter A", "Chapter B", "Chapter C")
        state.request?.sourceMangaTitle shouldBe "Source Manga"
        state.request?.targetMangaTitle shouldBe "Target Manga"
    }

    private suspend fun ImportRequestsScreenModel.awaitState(
        predicate: (ImportRequestsScreenModel.State) -> Boolean,
    ): ImportRequestsScreenModel.State {
        return withTimeout(1_000) {
            while (!predicate(state.value)) {
                delay(10)
            }
            state.value
        }
    }

    private suspend fun ImportRequestChaptersScreenModel.awaitState(
        predicate: (ImportRequestChaptersScreenModel.State) -> Boolean,
    ): ImportRequestChaptersScreenModel.State {
        return withTimeout(1_000) {
            while (!predicate(state.value)) {
                delay(10)
            }
            state.value
        }
    }

    private fun repository(
        summaries: List<VaultImportRequestSummary> = emptyList(),
        request: VaultImportRequest? = null,
    ): VaultRepository {
        return mockk {
            every { getImportRequestSummariesAsFlow() } returns MutableStateFlow(summaries)
            every { getImportRequestAsFlow(any()) } returns MutableStateFlow(request)
        }
    }

    private fun summary(
        id: Long = 1,
        totalChapters: Int = 0,
        pendingChapters: Int = 0,
        completedChapters: Int = 0,
        failedChapters: Int = 0,
        replacedChapters: Int = 0,
    ) = VaultImportRequestSummary(
        id = id,
        mangaId = 10,
        workflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
        targetMangaId = 20,
        createNewTitle = null,
        activeMangaIdentity = VaultIdentity("vault-manga"),
        activeManifestPath = "manga/vault-manga.json",
        createdAt = 100,
        updatedAt = 200,
        totalChapters = totalChapters,
        pendingChapters = pendingChapters,
        completedChapters = completedChapters,
        failedChapters = failedChapters,
        replacedChapters = replacedChapters,
        sourceMangaTitle = "Source Manga",
        sourceMangaSourceId = 99,
        sourceMangaFavorite = true,
        sourceMangaThumbnailUrl = "https://example.invalid/cover.jpg",
        sourceMangaCoverLastModified = 300,
        targetMangaTitle = "Target Manga",
    )

    private fun request(
        chapters: List<VaultImportRequestChapter>,
    ) = VaultImportRequest(
        id = 1,
        mangaId = 10,
        workflow = VaultImportRequestWorkflow.LIBRARY_CAPTURE,
        targetMangaId = null,
        createNewTitle = "New target",
        activeMangaIdentity = null,
        activeManifestPath = null,
        createdAt = 100,
        updatedAt = 200,
        chapters = chapters,
        sourceMangaTitle = "Source Manga",
        targetMangaTitle = "Target Manga",
    )

    private fun chapter(
        selectionId: String,
        sortOrder: Long,
    ) = VaultImportRequestChapter(
        chapterId = sortOrder,
        selectionId = selectionId,
        sortOrder = sortOrder,
        allowReplacement = false,
        state = VaultImportRequestChapterState.PENDING,
        chapterTitle = "Chapter ${selectionId.uppercase()}",
    )
}
