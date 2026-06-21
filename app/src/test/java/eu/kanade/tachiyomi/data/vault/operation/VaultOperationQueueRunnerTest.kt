package eu.kanade.tachiyomi.data.vault.operation

import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishGate
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshResult
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.repository.VaultRepository

class VaultOperationQueueRunnerTest {

    private val queueKey = VaultOperationQueueKey.forContentVault(ContentVaultIdentity("vault-a"))

    @Test
    fun `drains active queue jobs in fifo order`() = runTest {
        val repository = fakeRepository(
            job(id = 2, createdAt = 20, type = VaultTransferType.METADATA_PUBLISH),
            job(id = 1, createdAt = 10, type = VaultTransferType.METADATA_PUBLISH),
        )
        val handler = RecordingHandler(
            results = mutableListOf(
                VaultOperationExecutionResult(VaultTransferState.SUCCEEDED),
                VaultOperationExecutionResult(VaultTransferState.SUCCEEDED),
            ),
        )

        runner(repository.repository, handler).runOperations(queueKey)

        handler.executedJobIds shouldContainExactly listOf(1L, 2L)
        repository.jobState(1) shouldBe VaultTransferState.SUCCEEDED
        repository.jobState(2) shouldBe VaultTransferState.SUCCEEDED
    }

    @Test
    fun `continues after terminal semantic failure`() = runTest {
        val repository = fakeRepository(
            job(id = 1, createdAt = 10, type = VaultTransferType.METADATA_PUBLISH),
            job(id = 2, createdAt = 20, type = VaultTransferType.METADATA_PUBLISH),
        )
        val handler = RecordingHandler(
            results = mutableListOf(
                VaultOperationExecutionResult(VaultTransferState.FAILED, "manga_not_found"),
                VaultOperationExecutionResult(VaultTransferState.SUCCEEDED),
            ),
        )
        val refresher = FakeRefresher()
        val observer = RecordingObserver()

        runner(repository.repository, handler, refresher, observer).runOperations(queueKey)

        handler.executedJobIds shouldContainExactly listOf(1L, 2L)
        repository.jobState(1) shouldBe VaultTransferState.FAILED
        repository.jobState(2) shouldBe VaultTransferState.SUCCEEDED
        refresher.refreshCount shouldBe 1
        observer.events shouldContainExactly listOf(
            "queue_started",
            "job_started:1:METADATA_PUBLISH",
            "job_started:2:METADATA_PUBLISH",
            "refreshing",
            "queue_finished:1",
        )
    }

    @Test
    fun `refreshes and retries once after revision mismatch`() = runTest {
        val repository = fakeRepository(
            job(id = 1, createdAt = 10, type = VaultTransferType.METADATA_PUBLISH),
        )
        val handler = RecordingHandler(
            results = mutableListOf(
                VaultOperationExecutionResult(VaultTransferState.FAILED, "revision_mismatch"),
                VaultOperationExecutionResult(VaultTransferState.SUCCEEDED),
            ),
        )
        val refresher = FakeRefresher()
        val observer = RecordingObserver()

        runner(repository.repository, handler, refresher, observer).runOperations(queueKey)

        handler.executedJobIds shouldContainExactly listOf(1L, 1L)
        repository.jobState(1) shouldBe VaultTransferState.SUCCEEDED
        refresher.refreshCount shouldBe 2
        observer.events shouldContainExactly listOf(
            "queue_started",
            "job_started:1:METADATA_PUBLISH",
            "refreshing",
            "refreshing",
            "queue_finished:0",
        )
    }

    private fun runner(
        repository: VaultRepository,
        handler: RecordingHandler,
        refresher: FakeRefresher = FakeRefresher(),
        observer: VaultOperationQueueObserver = VaultOperationQueueObserver.None,
    ) = VaultOperationQueueRunner(
        repository = repository,
        handlers = listOf(handler),
        refreshService = refresher,
        publishGate = VaultManifestPublishGate(),
        now = { 100 },
        observer = observer,
    )

    private fun job(
        id: Long,
        createdAt: Long,
        type: VaultTransferType,
    ) = VaultTransferJob(
        id = id,
        vaultId = 1,
        mangaId = 10,
        chapterId = null,
        operationKey = "operation:$id",
        operationQueueKey = queueKey,
        payloadJson = "{}",
        type = type,
        state = VaultTransferState.QUEUED,
        remotePath = null,
        localPath = null,
        stagedPath = null,
        sizeBytes = null,
        checksumSha256 = null,
        failureReason = null,
        attempts = 0,
        createdAt = createdAt,
        updatedAt = createdAt,
        startedAt = null,
        completedAt = null,
    )

    private fun fakeRepository(vararg initialJobs: VaultTransferJob): RepositoryFixture {
        val jobs = initialJobs.associateBy { it.id }.toMutableMap()
        val repository = mockk<VaultRepository>()
        coEvery { repository.getActiveTransferJobsForOperationQueueKey(any()) } answers {
            val operationQueueKey = firstArg<String>()
            jobs.values
                .filter {
                    it.operationQueueKey == operationQueueKey &&
                        it.state in listOf(VaultTransferState.QUEUED, VaultTransferState.RUNNING)
                }
                .sortedWith(compareBy<VaultTransferJob> { it.createdAt }.thenBy { it.id })
        }
        coEvery { repository.upsertTransferJob(any()) } answers {
            val job = firstArg<VaultTransferJob>()
            jobs[job.id] = job
            job.id
        }
        every { repository.toString() } returns "FakeVaultRepository"
        return RepositoryFixture(repository, jobs)
    }

    private data class RepositoryFixture(
        val repository: VaultRepository,
        private val jobs: Map<Long, VaultTransferJob>,
    ) {
        fun jobState(id: Long): VaultTransferState = jobs.getValue(id).state
    }

    private class RecordingHandler(
        private val results: MutableList<VaultOperationExecutionResult>,
    ) : VaultOperationHandler {
        val executedJobIds = mutableListOf<Long>()

        override val type: VaultTransferType = VaultTransferType.METADATA_PUBLISH
        override val policy: VaultOperationPolicy = VaultOperationPolicy.OptimisticBackgroundPublish

        override suspend fun execute(job: VaultTransferJob, payloadJson: String): VaultOperationExecutionResult {
            executedJobIds += job.id
            return results.removeFirst()
        }
    }

    private class FakeRefresher : VaultCatalogueRefresher {
        var refreshCount = 0

        override suspend fun refreshConfiguredVault(): VaultCatalogueRefreshResult {
            refreshCount += 1
            return VaultCatalogueRefreshResult.Refreshed(
                identity = ContentVaultIdentity("vault-a"),
                mangaCount = 1,
                chapterCount = 1,
            )
        }
    }

    private class RecordingObserver : VaultOperationQueueObserver {
        val events = mutableListOf<String>()

        override fun onQueueStarted() {
            events += "queue_started"
        }

        override fun onOperationStarted(job: VaultTransferJob) {
            events += "job_started:${job.id}:${job.type}"
        }

        override fun onRefreshing() {
            events += "refreshing"
        }

        override fun onQueueFinished(failureCount: Int) {
            events += "queue_finished:$failureCount"
        }
    }
}
