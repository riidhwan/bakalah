package eu.kanade.tachiyomi.data.vault.operation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.workDataOf
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.repository.VaultRepository

internal interface VaultOperationQueueWakeup {
    fun wakeOperationQueue(operationQueueKey: String)
}

class VaultOperationManager(
    private val context: Context,
    private val repository: VaultRepository,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
) : VaultOperationQueueWakeup {

    suspend fun enqueueMetadataPublish(
        vaultId: Long,
        payload: VaultMetadataPublishPayload,
    ): VaultOperationEnqueueResult {
        val operationQueueKey = operationQueueKey(vaultId)
        val operationKey = metadataOperationKey(payload.mangaId)
        val payloadJson = json.encodeToString(payload)
        val jobId = coalesceOperation(
            vaultId = vaultId,
            mangaId = payload.mangaId,
            operationKey = operationKey,
            operationQueueKey = operationQueueKey,
            type = VaultTransferType.METADATA_PUBLISH,
            chapterId = null,
            payloadJson = payloadJson,
            coalesceQueued = true,
        )
        wakeOperationQueue(operationQueueKey)
        return VaultOperationEnqueueResult(
            jobId = jobId,
            operationKey = operationKey,
            operationQueueKey = operationQueueKey,
        )
    }

    suspend fun enqueueChapterDeletion(
        vaultId: Long,
        mangaId: Long,
        chapterId: Long,
        payload: VaultChapterDeletePayload,
    ): VaultOperationEnqueueResult {
        val operationQueueKey = operationQueueKey(vaultId)
        val operationKey = chapterDeletionOperationKey(mangaId)
        val payloadJson = json.encodeToString(payload)
        val jobId = coalesceOperation(
            vaultId = vaultId,
            mangaId = mangaId,
            chapterId = chapterId,
            operationKey = operationKey,
            operationQueueKey = operationQueueKey,
            type = VaultTransferType.CHAPTER_DELETE,
            payloadJson = payloadJson,
            coalesceQueued = false,
        )
        wakeOperationQueue(operationQueueKey)
        return VaultOperationEnqueueResult(
            jobId = jobId,
            operationKey = operationKey,
            operationQueueKey = operationQueueKey,
        )
    }

    private suspend fun coalesceOperation(
        vaultId: Long,
        mangaId: Long,
        chapterId: Long?,
        operationKey: String,
        operationQueueKey: String,
        type: VaultTransferType,
        payloadJson: String,
        coalesceQueued: Boolean,
    ): Long {
        val runningJob = repository.getActiveTransferJobsForOperationQueueKey(operationQueueKey)
            .firstOrNull { it.operationKey == operationKey && it.state == VaultTransferState.RUNNING }
        val queuedJob = repository
            .getQueuedTransferJobsForOperationQueueAndOperationKey(
                operationQueueKey = operationQueueKey,
                operationKey = operationKey,
            )
            .lastOrNull()
        val timestamp = now()
        val reusableJob = queuedJob.takeIf { coalesceQueued }
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
                    chapterId = chapterId,
                    importRequestId = null,
                    operationKey = operationKey,
                    operationQueueKey = operationQueueKey,
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
                wakeOperationQueue(operationQueueKey)
            }
        }
    }

    private suspend fun operationQueueKey(vaultId: Long): String {
        val vault = repository.getVault(vaultId) ?: error("content_vault_not_found")
        return VaultOperationQueueKey.forContentVault(vault.identity)
    }

    override fun wakeOperationQueue(operationQueueKey: String) {
        val request = OneTimeWorkRequest.Builder(VaultOperationWorker::class.java)
            .addTag(WORK_TAG)
            .addTag(tagForQueue(operationQueueKey))
            .setInputData(
                workDataOf(
                    VaultOperationWorker.OPERATION_QUEUE_KEY_INPUT to operationQueueKey,
                ),
            )
            .build()
        val workName = workName(operationQueueKey)
        val policy = if (context.workManager.hasRunningWork(workName)) {
            ExistingWorkPolicy.KEEP
        } else {
            ExistingWorkPolicy.REPLACE
        }
        context.workManager.enqueueUniqueWork(workName, policy, request)
    }

    companion object {
        private const val WORK_TAG = "VaultOperation"

        fun metadataOperationKey(mangaId: Long): String = "vault-metadata:$mangaId"

        fun chapterDeletionOperationKey(mangaId: Long): String = "vault-chapter-delete:$mangaId"

        fun workName(operationQueueKey: String): String = "$WORK_TAG:$operationQueueKey"

        fun tagForQueue(operationQueueKey: String): String = "$WORK_TAG:$operationQueueKey"
    }
}

private fun androidx.work.WorkManager.hasRunningWork(workName: String): Boolean {
    return runCatching {
        getWorkInfosForUniqueWork(workName).get().any { it.state == WorkInfo.State.RUNNING }
    }.getOrDefault(false)
}

data class VaultOperationEnqueueResult(
    val jobId: Long,
    val operationKey: String,
    val operationQueueKey: String,
)
