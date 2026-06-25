package eu.kanade.tachiyomi.data.vault.operation

import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterRenameResult
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterRenameService
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

class VaultChapterRenameOperationHandlerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `successful rename maps to succeeded job state`() = runTest {
        val service = mockk<VaultChapterRenameService> {
            coEvery {
                rename(
                    mangaId = 10,
                    chapterId = 20,
                    chapterIdentity = "chapter-20",
                    title = "New title",
                    ignoredJobId = 99,
                )
            } returns VaultChapterRenameResult.Renamed
        }
        val handler = VaultChapterRenameOperationHandler(json, service)

        val result = handler.execute(job(), payloadJson())

        result.state shouldBe VaultTransferState.SUCCEEDED
        result.failureReason shouldBe null
    }

    @Test
    fun `blank title failure maps to stable failure reason`() = runTest {
        val service = mockk<VaultChapterRenameService> {
            coEvery {
                rename(
                    mangaId = 10,
                    chapterId = 20,
                    chapterIdentity = "chapter-20",
                    title = "New title",
                    ignoredJobId = 99,
                )
            } returns VaultChapterRenameResult.TitleRequired
        }
        val handler = VaultChapterRenameOperationHandler(json, service)

        val result = handler.execute(job(), payloadJson())

        result.state shouldBe VaultTransferState.FAILED
        result.failureReason shouldBe "title_required"
    }

    @Test
    fun `invalid payload maps to invalid payload failure`() = runTest {
        val handler = VaultChapterRenameOperationHandler(json, mockk(relaxed = true))

        val result = handler.execute(job(), "{")

        result.state shouldBe VaultTransferState.FAILED
        result.failureReason shouldBe "invalid_payload"
    }

    private fun payloadJson(): String {
        return json.encodeToString(
            VaultChapterRenamePayload(
                mangaId = 10,
                chapterId = 20,
                chapterIdentity = "chapter-20",
                title = "New title",
            ),
        )
    }

    private fun job() = VaultTransferJob(
        id = 99,
        vaultId = 1,
        mangaId = 10,
        chapterId = 20,
        importRequestId = null,
        operationKey = "vault-chapter-rename:10:20",
        payloadJson = payloadJson(),
        type = VaultTransferType.CHAPTER_RENAME,
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
