package eu.kanade.tachiyomi.data.vault.capture

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultImportRequestChapterState
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent

class LibraryVaultCaptureServiceTest {

    @Test
    fun `captured chapter order uses latest first natural titles`() {
        val ordered = orderLibraryVaultCaptureChapters(
            chapters = listOf(
                manifestChapter("ep-19", "Ep.19 (ch. 19)"),
                manifestChapter("ep-20", "Ep.20 (ch. 20)"),
                manifestChapter("ep-8", "Ep.8 (ch. 8)"),
                manifestChapter("ep-9", "Ep.9 (ch. 9)"),
            ),
            replacementIdentities = emptySet(),
        )

        ordered.map { it.title } shouldBe listOf(
            "Ep.20 (ch. 20)",
            "Ep.19 (ch. 19)",
            "Ep.9 (ch. 9)",
            "Ep.8 (ch. 8)",
        )
        ordered.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L, 3L)
    }

    @Test
    fun `pending request chapters skip completed and failed rows in sort order`() {
        val request = importRequest(
            chapters = listOf(
                requestChapter("completed", 0, VaultImportRequestChapterState.COMPLETED),
                requestChapter("pending-2", 2),
                requestChapter("failed", 1, VaultImportRequestChapterState.FAILED),
                requestChapter("pending-1", 1),
            ),
        )

        request.pendingChapters().map { it.selectionId } shouldBe listOf("pending-1", "pending-2")
    }

    @Test
    fun `checkpoint summary includes rows processed before restart`() {
        val request = importRequest(
            chapters = listOf(
                requestChapter("added", 0, VaultImportRequestChapterState.COMPLETED),
                requestChapter("replaced", 1, VaultImportRequestChapterState.COMPLETED, isReplaced = true),
                requestChapter(
                    selectionId = "failed",
                    sortOrder = 2,
                    state = VaultImportRequestChapterState.FAILED,
                    failureCategory = "upload",
                ),
                requestChapter("pending", 3),
            ),
        )

        val summary = request.checkpointSummary()

        summary.added shouldBe 1
        summary.replaced shouldBe 1
        summary.failures.map { it.title to it.category } shouldBe listOf("failed" to "upload")
    }

    private fun manifestChapter(
        identity: String,
        title: String,
        sourceOrder: Long = 0,
    ) = VaultManifestChapter(
        identity = identity,
        title = title,
        chapterNumber = -1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = sourceOrder,
        content = VaultManifestChapterContent(
            path = "content/manga/$identity/$title.cbz",
            format = VaultChapterContentFormat.CBZ,
            integrity = VaultContentIntegrity(
                sizeBytes = 1,
                checksumSha256 = identity,
            ),
        ),
        revisionId = "revision-$identity",
        revisionNumber = 1,
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun importRequest(
        chapters: List<VaultImportRequestChapter>,
    ) = VaultImportRequest(
        id = 1,
        mangaId = 2,
        workflow = VaultImportRequestWorkflow.LIBRARY_CAPTURE,
        targetMangaId = null,
        createNewTitle = null,
        createdAt = 1,
        updatedAt = 1,
        chapters = chapters,
    )

    private fun requestChapter(
        selectionId: String,
        sortOrder: Long,
        state: VaultImportRequestChapterState = VaultImportRequestChapterState.PENDING,
        isReplaced: Boolean = false,
        failureCategory: String? = null,
    ) = VaultImportRequestChapter(
        chapterId = null,
        selectionId = selectionId,
        sortOrder = sortOrder,
        allowReplacement = false,
        state = state,
        isReplaced = isReplaced,
        failureCategory = failureCategory,
        processedAt = if (state == VaultImportRequestChapterState.PENDING) null else 10,
    )
}
