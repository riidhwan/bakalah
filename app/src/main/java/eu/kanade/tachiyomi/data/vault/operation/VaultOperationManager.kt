package eu.kanade.tachiyomi.data.vault.operation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.workDataOf
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.repository.VaultRepository

class VaultOperationManager(
    private val context: Context,
    private val repository: VaultRepository,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun enqueueMetadataPublish(
        vaultId: Long,
        payload: VaultMetadataPublishPayload,
    ): VaultOperationEnqueueResult {
        val operationKey = metadataOperationKey(payload.mangaId)
        val payloadJson = json.encodeToString(payload)
        val jobId = coalesceOperation(
            vaultId = vaultId,
            mangaId = payload.mangaId,
            operationKey = operationKey,
            type = VaultTransferType.METADATA_PUBLISH,
            payloadJson = payloadJson,
        )
        enqueueWorker(operationKey)
        return VaultOperationEnqueueResult(jobId = jobId, operationKey = operationKey)
    }

    private suspend fun coalesceOperation(
        vaultId: Long,
        mangaId: Long,
        operationKey: String,
        type: VaultTransferType,
        payloadJson: String,
    ): Long {
        val activeJobs = repository.getActiveTransferJobsForOperationKey(operationKey)
        val runningJob = activeJobs.firstOrNull { it.state == VaultTransferState.RUNNING }
        val queuedJob = activeJobs.lastOrNull { it.state == VaultTransferState.QUEUED }
        val timestamp = now()
        val reusableJob = queuedJob
        return if (reusableJob != null) {
            repository.upsertTransferJob(
                reusableJob.copy(
                    payloadJson = payloadJson,
                    failureReason = null,
                    updatedAt = timestamp,
                    completedAt = null,
                ),
            )
        } else {
            repository.upsertTransferJob(
                VaultTransferJob(
                    id = -1,
                    vaultId = vaultId,
                    mangaId = mangaId,
                    chapterId = null,
                    importRequestId = null,
                    operationKey = operationKey,
                    payloadJson = payloadJson,
                    type = type,
                    state = VaultTransferState.QUEUED,
                    remotePath = null,
                    localPath = null,
                    stagedPath = null,
                    sizeBytes = null,
                    checksumSha256 = null,
                    failureReason = null,
                    attempts = 0,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    startedAt = null,
                    completedAt = null,
                ),
            )
        }.also {
            if (runningJob != null) {
                enqueueWorker(operationKey)
            }
        }
    }

    private fun enqueueWorker(operationKey: String) {
        val request = OneTimeWorkRequest.Builder(VaultOperationWorker::class.java)
            .addTag(WORK_TAG)
            .addTag(tagForOperation(operationKey))
            .setInputData(
                workDataOf(
                    VaultOperationWorker.OPERATION_KEY_INPUT to operationKey,
                ),
            )
            .build()
        context.workManager.enqueueUniqueWork(workName(operationKey), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        private const val WORK_TAG = "VaultOperation"

        fun metadataOperationKey(mangaId: Long): String = "vault-metadata:$mangaId"

        fun workName(operationKey: String): String = "$WORK_TAG:$operationKey"

        fun tagForOperation(operationKey: String): String = "$WORK_TAG:$operationKey"
    }
}

data class VaultOperationEnqueueResult(
    val jobId: Long,
    val operationKey: String,
)
