package eu.kanade.tachiyomi.data.vault.operation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.repository.VaultRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VaultOperationWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val repository: VaultRepository = Injekt.get()
    private val handlers: List<VaultOperationHandler> = Injekt.get()

    override suspend fun doWork(): Result {
        val operationKey = inputData.getString(OPERATION_KEY_INPUT) ?: return Result.failure()
        return try {
            runOperations(operationKey)
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Vault operation worker failed for operationKey=$operationKey" }
            Result.retry()
        }
    }

    private suspend fun runOperations(operationKey: String) {
        while (true) {
            val job = nextActiveJob(operationKey) ?: return
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
            val result = handler.execute(payloadJson)
            markCompleted(runningJob, result)
        }
    }

    private suspend fun nextActiveJob(operationKey: String): VaultTransferJob? {
        val activeJobs = repository.getActiveTransferJobsForOperationKey(operationKey)
        return activeJobs.firstOrNull { it.state == VaultTransferState.RUNNING }
            ?: activeJobs.firstOrNull { it.state == VaultTransferState.QUEUED }
    }

    private suspend fun markRunning(job: VaultTransferJob): VaultTransferJob {
        if (job.state == VaultTransferState.RUNNING) return job
        val timestamp = System.currentTimeMillis()
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
        val timestamp = System.currentTimeMillis()
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
        val timestamp = System.currentTimeMillis()
        repository.upsertTransferJob(
            job.copy(
                state = VaultTransferState.FAILED,
                failureReason = failureReason,
                updatedAt = timestamp,
                completedAt = timestamp,
            ),
        )
    }

    companion object {
        const val OPERATION_KEY_INPUT = "operation_key"
    }
}
