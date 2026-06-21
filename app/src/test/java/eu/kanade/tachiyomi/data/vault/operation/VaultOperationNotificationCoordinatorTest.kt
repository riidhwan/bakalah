package eu.kanade.tachiyomi.data.vault.operation

import io.mockk.coEvery
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.repository.VaultRepository

class VaultOperationNotificationCoordinatorTest {

    private val queueKey = VaultOperationQueueKey.forContentVault(
        tachiyomi.domain.vault.model.ContentVaultIdentity("vault-a"),
    )

    @Test
    fun `queued operation shows queue notification`() = runTest {
        val repository = repository(activeJobs = listOf(job(state = VaultTransferState.QUEUED)))
        val notifier = notifier()
        val coordinator = VaultOperationNotificationCoordinator(repository, notifier, completionDelayMillis = 0)

        coordinator.refresh(queueKey)

        verify { notifier.showQueueRunning() }
    }

    @Test
    fun `running operation shows operation notification text`() = runTest {
        val repository = repository(activeJobs = listOf(job(state = VaultTransferState.RUNNING)))
        val notifier = notifier()
        val coordinator = VaultOperationNotificationCoordinator(repository, notifier, completionDelayMillis = 0)

        coordinator.refresh(queueKey)

        verify { notifier.showOperationRunning(VaultTransferType.CHAPTER_DELETE) }
    }

    @Test
    fun `successful finish keeps progress notification when queue has new active work`() = runTest {
        val repository = repository(activeJobs = listOf(job(state = VaultTransferState.QUEUED)))
        val notifier = notifier()
        val coordinator = VaultOperationNotificationCoordinator(repository, notifier, completionDelayMillis = 0)

        coordinator.finishDrain(queueKey, failureCount = 0)

        verify(exactly = 0) { notifier.cancel() }
        verify { notifier.showQueueRunning() }
    }

    @Test
    fun `successful finish cancels progress notification when queue is empty`() = runTest {
        val repository = repository(activeJobs = emptyList())
        val notifier = notifier()
        val coordinator = VaultOperationNotificationCoordinator(repository, notifier, completionDelayMillis = 0)

        coordinator.finishDrain(queueKey, failureCount = 0)

        verify { notifier.cancel() }
    }

    private fun repository(activeJobs: List<VaultTransferJob>): VaultRepository {
        return mockk {
            coEvery { getActiveTransferJobsForOperationQueueKey(queueKey) } returns activeJobs
            coEvery { hasActiveTransferJobsForOperationQueueKey(queueKey) } returns activeJobs.isNotEmpty()
        }
    }

    private fun notifier(): VaultOperationNotifier {
        return mockk {
            justRun { showQueueRunning() }
            justRun { showOperationRunning(any()) }
            justRun { showRefreshing() }
            justRun { showFailures(any()) }
            justRun { cancel() }
        }
    }

    private fun job(
        state: VaultTransferState,
    ) = VaultTransferJob(
        id = 1,
        vaultId = 1,
        mangaId = 10,
        chapterId = 20,
        importRequestId = null,
        operationKey = "vault-chapter-delete:10",
        operationQueueKey = queueKey,
        payloadJson = "{}",
        type = VaultTransferType.CHAPTER_DELETE,
        state = state,
        remotePath = null,
        localPath = null,
        stagedPath = null,
        sizeBytes = null,
        checksumSha256 = null,
        failureReason = null,
        attempts = 0,
        createdAt = 1,
        updatedAt = 1,
        startedAt = null,
        completedAt = null,
    )
}
