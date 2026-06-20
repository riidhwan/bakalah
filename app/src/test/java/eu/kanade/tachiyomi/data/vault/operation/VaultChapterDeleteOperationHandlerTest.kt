package eu.kanade.tachiyomi.data.vault.operation

import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterDeletionResult
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterDeletionService
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType

class VaultChapterDeleteOperationHandlerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `successful deletion maps to succeeded job state`() = runTest {
        val service = mockk<VaultChapterDeletionService> {
            coEvery {
                delete(mangaId = 10, chapterId = 20, ignoredJobId = 99)
            } returns VaultChapterDeletionResult.Deleted
        }
        val handler = VaultChapterDeleteOperationHandler(json, service)

        val result = handler.execute(job(), payloadJson())

        result.state shouldBe VaultTransferState.SUCCEEDED
        result.failureReason shouldBe null
    }

    @Test
    fun `cleanup failure maps to partially succeeded job state`() = runTest {
        val service = mockk<VaultChapterDeletionService> {
            coEvery {
                delete(mangaId = 10, chapterId = 20, ignoredJobId = 99)
            } returns VaultChapterDeletionResult.DeletedWithCleanupFailures(listOf("content/chapter.cbz"))
        }
        val handler = VaultChapterDeleteOperationHandler(json, service)

        val result = handler.execute(job(), payloadJson())

        result.state shouldBe VaultTransferState.PARTIALLY_SUCCEEDED
        result.failureReason shouldBe "cleanup_failed:1"
    }

    @Test
    fun `last chapter failure maps to stable failure reason`() = runTest {
        val service = mockk<VaultChapterDeletionService> {
            coEvery {
                delete(mangaId = 10, chapterId = 20, ignoredJobId = 99)
            } returns VaultChapterDeletionResult.LastChapter
        }
        val handler = VaultChapterDeleteOperationHandler(json, service)

        val result = handler.execute(job(), payloadJson())

        result.state shouldBe VaultTransferState.FAILED
        result.failureReason shouldBe "last_chapter"
    }

    private fun payloadJson(): String {
        return json.encodeToString(
            VaultChapterDeletePayload(
                mangaId = 10,
                chapterId = 20,
                chapterTitle = "Chapter 20",
            ),
        )
    }

    private fun job() = VaultTransferJob(
        id = 99,
        vaultId = 1,
        mangaId = 10,
        chapterId = 20,
        importRequestId = null,
        operationKey = "vault-chapter-delete:10",
        payloadJson = payloadJson(),
        type = VaultTransferType.CHAPTER_DELETE,
        state = VaultTransferState.RUNNING,
        remotePath = null,
        localPath = null,
        stagedPath = null,
        sizeBytes = null,
        checksumSha256 = null,
        failureReason = null,
        attempts = 1,
        createdAt = 1,
        updatedAt = 1,
        startedAt = 1,
        completedAt = null,
    )
}
