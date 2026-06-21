package eu.kanade.tachiyomi.data.vault.operation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishGate
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.vault.model.VaultTransferJob
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
    private val notifier = VaultOperationNotifier(context)
    private val notificationObserver = NotificationQueueObserver(notifier)
    private val queueRunner = VaultOperationQueueRunner(
        repository = repository,
        handlers = handlers,
        refreshService = refreshService,
        publishGate = publishGate,
        observer = notificationObserver,
    )

    override suspend fun doWork(): Result {
        val operationQueueKey = inputData.getString(OPERATION_QUEUE_KEY_INPUT) ?: return Result.failure()
        if (VaultOperationQueueKey.contentVaultIdentity(operationQueueKey) == null) return Result.failure()
        setForegroundSafely()
        return try {
            queueRunner.runOperations(operationQueueKey)
            notificationObserver.finish()
            Result.success()
        } catch (e: Exception) {
            notifier.cancel()
            logcat(LogPriority.ERROR, e) { "Vault operation worker failed for operationQueueKey=$operationQueueKey" }
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_VAULT_OPERATION_PROGRESS,
            notifier.foregroundNotification().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val OPERATION_QUEUE_KEY_INPUT = "operation_queue_key"
    }
}

private class NotificationQueueObserver(
    private val notifier: VaultOperationNotifier,
) : VaultOperationQueueObserver {
    private var failureCount: Int = 0

    override fun onQueueStarted() {
        notifier.showQueueRunning()
    }

    override fun onOperationStarted(job: VaultTransferJob) {
        notifier.showOperationRunning(job.type)
    }

    override fun onRefreshing() {
        notifier.showRefreshing()
    }

    override fun onQueueFinished(failureCount: Int) {
        this.failureCount = failureCount
    }

    suspend fun finish() {
        if (failureCount > 0) {
            notifier.showFailures(failureCount)
        } else {
            delay(MIN_SUCCESS_NOTIFICATION_VISIBLE_MILLIS)
            notifier.cancel()
        }
    }
}

private const val MIN_SUCCESS_NOTIFICATION_VISIBLE_MILLIS = 1_500L
