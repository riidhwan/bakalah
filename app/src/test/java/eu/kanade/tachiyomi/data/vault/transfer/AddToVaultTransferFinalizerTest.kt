package eu.kanade.tachiyomi.data.vault.transfer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType

class AddToVaultTransferFinalizerTest {

    @Test
    fun `complete with all success records succeeded transfer`() {
        val finalized = AddToVaultTransferFinalizer.complete(
            job = runningJob(),
            added = 2,
            replaced = 1,
            failures = emptyList(),
            completedAt = 200,
        )

        finalized.state shouldBe VaultTransferState.SUCCEEDED
        finalized.failureReason shouldBe null
        finalized.addedCount shouldBe 2
        finalized.replacedCount shouldBe 1
        finalized.failedCount shouldBe 0
        finalized.cancelledCount shouldBe 0
        finalized.detailJson shouldBe null
        finalized.updatedAt shouldBe 200
        finalized.completedAt shouldBe 200
    }

    @Test
    fun `complete with some success and some failure records partial success without failure reason`() {
        val finalized = AddToVaultTransferFinalizer.complete(
            job = runningJob(),
            added = 1,
            replaced = 0,
            failures = listOf(AddToVaultChapterFailure("Chapter 2", "upload")),
            completedAt = 200,
        )

        finalized.state shouldBe VaultTransferState.PARTIALLY_SUCCEEDED
        finalized.failureReason shouldBe null
        finalized.addedCount shouldBe 1
        finalized.replacedCount shouldBe 0
        finalized.failedCount shouldBe 1
        finalized.detailJson shouldBe """[{"title":"Chapter 2","category":"upload"}]"""
        finalized.completedAt shouldBe 200
    }

    @Test
    fun `complete with only failures records failed transfer with first failure reason`() {
        val finalized = AddToVaultTransferFinalizer.complete(
            job = runningJob(),
            added = 0,
            replaced = 0,
            failures = listOf(
                AddToVaultChapterFailure("Missing", "missing_chapter"),
                AddToVaultChapterFailure("Duplicate", "unconfirmed_duplicate"),
            ),
            completedAt = 200,
        )

        finalized.state shouldBe VaultTransferState.FAILED
        finalized.failureReason shouldBe "missing_chapter"
        finalized.failedCount shouldBe 2
        finalized.detailJson shouldBe
            """[{"title":"Missing","category":"missing_chapter"}, {"title":"Duplicate","category":"unconfirmed_duplicate"}]"""
    }

    @Test
    fun `cancel records cancelled transfer counts and failure details`() {
        val finalized = AddToVaultTransferFinalizer.cancel(
            job = runningJob(),
            selectedCount = 4,
            added = 1,
            replaced = 1,
            failures = listOf(AddToVaultChapterFailure("Bad chapter", "staging")),
            completedAt = 200,
        )

        finalized.state shouldBe VaultTransferState.CANCELLED
        finalized.addedCount shouldBe 1
        finalized.replacedCount shouldBe 1
        finalized.failedCount shouldBe 1
        finalized.cancelledCount shouldBe 1
        finalized.detailJson shouldBe """[{"title":"Bad chapter","category":"staging"}]"""
        finalized.completedAt shouldBe 200
    }

    @Test
    fun `global failure after partial success records partial success and cancelled remainder`() {
        val finalized = AddToVaultTransferFinalizer.stopAfterGlobalFailure(
            job = runningJob(),
            selectedCount = 5,
            added = 1,
            replaced = 1,
            failures = listOf(AddToVaultChapterFailure("Earlier", "upload")),
            globalFailure = AddToVaultChapterFailure("Stopped", "identity"),
            completedAt = 200,
        )

        finalized.state shouldBe VaultTransferState.PARTIALLY_SUCCEEDED
        finalized.failureReason shouldBe "identity"
        finalized.addedCount shouldBe 1
        finalized.replacedCount shouldBe 1
        finalized.failedCount shouldBe 2
        finalized.cancelledCount shouldBe 1
        finalized.detailJson shouldBe
            """[{"title":"Earlier","category":"upload"}, {"title":"Stopped","category":"identity"}]"""
    }

    @Test
    fun `failure detail json escapes user supplied text`() {
        val detailJson = listOf(
            AddToVaultChapterFailure("A \"quoted\"\nchapter\\title", "bad\tcategory"),
        ).toDetailJson()

        detailJson shouldBe """[{"title":"A \"quoted\"\nchapter\\title","category":"bad\tcategory"}]"""
    }

    private fun runningJob() = VaultTransferJob(
        id = 1,
        vaultId = 2,
        chapterId = null,
        type = VaultTransferType.IMPORT_PUBLISH,
        state = VaultTransferState.RUNNING,
        remotePath = null,
        localPath = null,
        stagedPath = null,
        sizeBytes = null,
        checksumSha256 = null,
        failureReason = null,
        attempts = 1,
        createdAt = 100,
        updatedAt = 100,
        startedAt = 100,
        completedAt = null,
    )
}
