package eu.kanade.tachiyomi.data.vault

import androidx.work.ListenableWorker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.domain.vault.repository.VaultRepository

class AddToVaultJobRunnerTest {

    private val manga = Manga.create().copy(id = 7, title = "Manga")

    @Test
    fun `missing request id fails without cleanup`() = runTest {
        val fixture = fixture()

        val result = fixture.runner.run(
            requestId = null,
            expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
            workflowName = "Local-to-Vault Import",
            setForeground = fixture.callbacks::setForeground,
            showPreparing = fixture.callbacks::showPreparing,
            showError = fixture.callbacks::showError,
            showCancelled = fixture.callbacks::showCancelled,
        ) { _, _ -> ListenableWorker.Result.success() }

        (result is ListenableWorker.Result.Failure) shouldBe true
        coVerify(exactly = 0) { fixture.repository.deleteImportRequest(any()) }
        fixture.callbacks shouldBe Callbacks()
    }

    @Test
    fun `missing request fails without cleanup`() = runTest {
        val fixture = fixture(request = null)

        val result = fixture.runner.run(
            requestId = 1,
            expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
            workflowName = "Local-to-Vault Import",
            setForeground = fixture.callbacks::setForeground,
            showPreparing = fixture.callbacks::showPreparing,
            showError = fixture.callbacks::showError,
            showCancelled = fixture.callbacks::showCancelled,
        ) { _, _ -> ListenableWorker.Result.success() }

        (result is ListenableWorker.Result.Failure) shouldBe true
        coVerify(exactly = 0) { fixture.repository.deleteImportRequest(any()) }
        fixture.callbacks shouldBe Callbacks()
    }

    @Test
    fun `wrong workflow deletes request and fails`() = runTest {
        val fixture = fixture(request = request(workflow = VaultImportRequestWorkflow.LIBRARY_CAPTURE))

        val result = fixture.runner.run(
            requestId = 1,
            expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
            workflowName = "Local-to-Vault Import",
            setForeground = fixture.callbacks::setForeground,
            showPreparing = fixture.callbacks::showPreparing,
            showError = fixture.callbacks::showError,
            showCancelled = fixture.callbacks::showCancelled,
        ) { _, _ -> ListenableWorker.Result.success() }

        (result is ListenableWorker.Result.Failure) shouldBe true
        coVerify(exactly = 1) { fixture.repository.deleteImportRequest(1) }
        fixture.callbacks shouldBe Callbacks()
    }

    @Test
    fun `missing manga deletes request and fails`() = runTest {
        val fixture = fixture(manga = null)

        val result = fixture.runner.run(
            requestId = 1,
            expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
            workflowName = "Local-to-Vault Import",
            setForeground = fixture.callbacks::setForeground,
            showPreparing = fixture.callbacks::showPreparing,
            showError = fixture.callbacks::showError,
            showCancelled = fixture.callbacks::showCancelled,
        ) { _, _ -> ListenableWorker.Result.success() }

        (result is ListenableWorker.Result.Failure) shouldBe true
        coVerify(exactly = 1) { fixture.repository.deleteImportRequest(1) }
        fixture.callbacks shouldBe Callbacks()
    }

    @Test
    fun `success prepares workflow and deletes request`() = runTest {
        val fixture = fixture()

        val result = fixture.runner.run(
            requestId = 1,
            expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
            workflowName = "Local-to-Vault Import",
            setForeground = fixture.callbacks::setForeground,
            showPreparing = fixture.callbacks::showPreparing,
            showError = fixture.callbacks::showError,
            showCancelled = fixture.callbacks::showCancelled,
        ) { request, manga ->
            request.id shouldBe 1
            manga.title shouldBe "Manga"
            ListenableWorker.Result.success()
        }

        (result is ListenableWorker.Result.Success) shouldBe true
        coVerify(exactly = 1) { fixture.repository.deleteImportRequest(1) }
        fixture.callbacks shouldBe Callbacks(foreground = 1, preparingTitles = listOf("Manga"))
    }

    @Test
    fun `workflow failure shows error and deletes request`() = runTest {
        val fixture = fixture()

        val result = fixture.runner.run(
            requestId = 1,
            expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
            workflowName = "Local-to-Vault Import",
            setForeground = fixture.callbacks::setForeground,
            showPreparing = fixture.callbacks::showPreparing,
            showError = fixture.callbacks::showError,
            showCancelled = fixture.callbacks::showCancelled,
        ) { _, _ -> ListenableWorker.Result.failure() }

        (result is ListenableWorker.Result.Failure) shouldBe true
        coVerify(exactly = 1) { fixture.repository.deleteImportRequest(1) }
        fixture.callbacks shouldBe Callbacks(foreground = 1, preparingTitles = listOf("Manga"), errors = 1)
    }

    @Test
    fun `workflow exception shows error and deletes request`() = runTest {
        val fixture = fixture()

        val result = fixture.runner.run(
            requestId = 1,
            expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
            workflowName = "Local-to-Vault Import",
            setForeground = fixture.callbacks::setForeground,
            showPreparing = fixture.callbacks::showPreparing,
            showError = fixture.callbacks::showError,
            showCancelled = fixture.callbacks::showCancelled,
        ) { _, _ -> error("boom") }

        (result is ListenableWorker.Result.Failure) shouldBe true
        coVerify(exactly = 1) { fixture.repository.deleteImportRequest(1) }
        fixture.callbacks shouldBe Callbacks(foreground = 1, preparingTitles = listOf("Manga"), errors = 1)
    }

    @Test
    fun `workflow cancellation shows cancelled deletes request and rethrows`() = runTest {
        val fixture = fixture()

        var cancellationThrown = false
        try {
            fixture.runner.run(
                requestId = 1,
                expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
                workflowName = "Local-to-Vault Import",
                setForeground = fixture.callbacks::setForeground,
                showPreparing = fixture.callbacks::showPreparing,
                showError = fixture.callbacks::showError,
                showCancelled = fixture.callbacks::showCancelled,
            ) { _, _ -> throw CancellationException("cancelled") }
        } catch (e: CancellationException) {
            cancellationThrown = true
        }

        cancellationThrown shouldBe true
        coVerify(exactly = 1) { fixture.repository.deleteImportRequest(1) }
        fixture.callbacks shouldBe Callbacks(foreground = 1, preparingTitles = listOf("Manga"), cancellations = 1)
    }

    @Test
    fun `create request persists workflow and selected chapters`() = runTest {
        val fixture = fixture()
        val chapter = VaultImportRequestChapter(
            chapterId = 99,
            selectionId = "chapter-99",
            sortOrder = 3,
            allowReplacement = true,
        )
        coEvery { fixture.repository.insertImportRequest(any()) } returns 42

        val id = fixture.runner.createRequest(
            mangaId = 7,
            workflow = VaultImportRequestWorkflow.LIBRARY_CAPTURE,
            selectedChapters = listOf(chapter),
            targetMangaId = 11,
            createNewTitle = null,
        )

        id shouldBe 42
        coVerify(exactly = 1) {
            fixture.repository.insertImportRequest(
                match {
                    it.mangaId == 7L &&
                        it.workflow == VaultImportRequestWorkflow.LIBRARY_CAPTURE &&
                        it.targetMangaId == 11L &&
                        it.createNewTitle == null &&
                        it.chapters == listOf(chapter)
                },
            )
        }
    }

    private fun fixture(
        request: VaultImportRequest? = request(),
        manga: Manga? = this.manga,
    ): Fixture {
        val repository = mockk<VaultRepository> {
            coEvery { getImportRequest(1) } returns request
            coEvery { deleteImportRequest(any()) } returns Unit
            coEvery { insertImportRequest(any()) } returns 1
        }
        val mangaRepository = mockk<MangaRepository> {
            if (manga != null) {
                coEvery { getMangaById(7) } returns manga
            } else {
                coEvery { getMangaById(7) } throws NoSuchElementException()
            }
        }
        return Fixture(
            repository = repository,
            runner = AddToVaultJobRunner(GetManga(mangaRepository), repository),
            callbacks = Callbacks(),
        )
    }

    private fun request(
        workflow: VaultImportRequestWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
    ) = VaultImportRequest(
        id = 1,
        mangaId = 7,
        workflow = workflow,
        targetMangaId = null,
        createNewTitle = "Manga",
        createdAt = 1,
        updatedAt = 1,
        chapters = emptyList(),
    )

    private data class Fixture(
        val repository: VaultRepository,
        val runner: AddToVaultJobRunner,
        val callbacks: Callbacks,
    )

    private data class Callbacks(
        var foreground: Int = 0,
        var preparingTitles: List<String> = emptyList(),
        var errors: Int = 0,
        var cancellations: Int = 0,
    ) {
        fun setForeground() {
            foreground += 1
        }

        fun showPreparing(title: String) {
            preparingTitles = preparingTitles + title
        }

        fun showError() {
            errors += 1
        }

        fun showCancelled() {
            cancellations += 1
        }
    }
}
