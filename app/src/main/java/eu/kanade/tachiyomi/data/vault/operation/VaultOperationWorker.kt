package eu.kanade.tachiyomi.data.vault.operation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishGate
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.vault.repository.VaultRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VaultOperationWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val repository: VaultRepository = Injekt.get()
    private val handlers: List<VaultOperationHandler> = Injekt.get()
    private val refreshService: VaultCatalogueRefresher = Injekt.get()
    private val publishGate: VaultManifestPublishGate = Injekt.get()
    private val queueRunner = VaultOperationQueueRunner(repository, handlers, refreshService, publishGate)

    override suspend fun doWork(): Result {
        val operationQueueKey = inputData.getString(OPERATION_QUEUE_KEY_INPUT) ?: return Result.failure()
        return try {
            queueRunner.runOperations(operationQueueKey)
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Vault operation worker failed for operationQueueKey=$operationQueueKey" }
            Result.retry()
        }
    }

    companion object {
        const val OPERATION_QUEUE_KEY_INPUT = "operation_queue_key"
    }
}
