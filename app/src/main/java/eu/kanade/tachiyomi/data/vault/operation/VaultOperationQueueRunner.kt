package eu.kanade.tachiyomi.data.vault.operation

import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishGate
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshResult
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.repository.VaultRepository

class VaultOperationQueueRunner(
    private val repository: VaultRepository,
    private val handlers: List<VaultOperationHandler>,
    private val refreshService: VaultCatalogueRefresher,
    private val publishGate: VaultManifestPublishGate,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun runOperations(operationQueueKey: String) {
        val identity = VaultOperationQueueKey.contentVaultIdentity(operationQueueKey) ?: return
        while (true) {
            val job = nextActiveJob(operationQueueKey) ?: return
            if (job.isTerminal) return
            val handler = handlers.firstOrNull { it.type == job.type }
            if (handler == null) {
                markFailed(job, "missing_handler")
                continue
            }
            val payloadJson = job.payloadJson
            if (payloadJson == null) {
                markFailed(job, "missing_payload")
                continue
            }

            val runningJob = markRunning(job)
            val result = publishGate.withGate(identity) {
                executeWithFreshnessRetry(handler, runningJob, payloadJson)
            }
            markCompleted(runningJob, result)
        }
    }

    private suspend fun executeWithFreshnessRetry(
        handler: VaultOperationHandler,
        job: VaultTransferJob,
        payloadJson: String,
    ): VaultOperationExecutionResult {
        val first = handler.execute(job, payloadJson)
        if (first.failureReason != REVISION_MISMATCH_REASON) return refreshAfterSuccess(first)
        if (refreshService.refreshConfiguredVault() !is VaultCatalogueRefreshResult.Refreshed) return first
        return refreshAfterSuccess(handler.execute(job, payloadJson))
    }

    private suspend fun refreshAfterSuccess(result: VaultOperationExecutionResult): VaultOperationExecutionResult {
        if (result.state != VaultTransferState.SUCCEEDED &&
            result.state != VaultTransferState.PARTIALLY_SUCCEEDED
        ) {
            return result
        }
        return when (refreshService.refreshConfiguredVault()) {
            is VaultCatalogueRefreshResult.Refreshed -> result
            VaultCatalogueRefreshResult.IncompleteConfiguration -> result.copy(
                state = VaultTransferState.FAILED,
                failureReason = "refresh_incomplete_configuration",
            )
            VaultCatalogueRefreshResult.NotVault -> result.copy(
                state = VaultTransferState.FAILED,
                failureReason = "refresh_not_vault",
            )
            is VaultCatalogueRefreshResult.UnsupportedOlderVersion -> result.copy(
                state = VaultTransferState.FAILED,
                failureReason = "refresh_unsupported_older_version",
            )
            is VaultCatalogueRefreshResult.UnsupportedNewerVersion -> result.copy(
                state = VaultTransferState.FAILED,
                failureReason = "refresh_unsupported_newer_version",
            )
            is VaultCatalogueRefreshResult.IdentityChanged -> result.copy(
                state = VaultTransferState.FAILED,
                failureReason = "refresh_identity_changed",
            )
            is VaultCatalogueRefreshResult.ManifestNotFound -> result.copy(
                state = VaultTransferState.FAILED,
                failureReason = "refresh_manifest_not_found",
            )
            is VaultCatalogueRefreshResult.IdentityMismatch -> result.copy(
                state = VaultTransferState.FAILED,
                failureReason = "refresh_identity_mismatch",
            )
            is VaultCatalogueRefreshResult.Malformed -> result.copy(
                state = VaultTransferState.FAILED,
                failureReason = "refresh_malformed_manifest",
            )
        }
    }

    private suspend fun nextActiveJob(operationQueueKey: String): VaultTransferJob? {
        val activeJobs = repository.getActiveTransferJobsForOperationQueueKey(operationQueueKey)
        return activeJobs.firstOrNull { it.state == VaultTransferState.RUNNING }
            ?: activeJobs.firstOrNull { it.state == VaultTransferState.QUEUED }
    }

    private suspend fun markRunning(job: VaultTransferJob): VaultTransferJob {
        if (job.state == VaultTransferState.RUNNING) return job
        val timestamp = now()
        val runningJob = job.copy(
            state = VaultTransferState.RUNNING,
            attempts = job.attempts + 1,
            failureReason = null,
            updatedAt = timestamp,
            startedAt = job.startedAt ?: timestamp,
            completedAt = null,
        )
        repository.upsertTransferJob(runningJob)
        return runningJob
    }

    private suspend fun markCompleted(job: VaultTransferJob, result: VaultOperationExecutionResult) {
        val timestamp = now()
        repository.upsertTransferJob(
            job.copy(
                state = result.state,
                failureReason = result.failureReason,
                updatedAt = timestamp,
                completedAt = timestamp,
            ),
        )
    }

    private suspend fun markFailed(job: VaultTransferJob, failureReason: String) {
        val timestamp = now()
        repository.upsertTransferJob(
            job.copy(
                state = VaultTransferState.FAILED,
                failureReason = failureReason,
                updatedAt = timestamp,
                completedAt = timestamp,
            ),
        )
    }

    private companion object {
        const val REVISION_MISMATCH_REASON = "revision_mismatch"
    }
}
