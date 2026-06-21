package eu.kanade.tachiyomi.data.vault.operation

import kotlinx.coroutines.delay
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.repository.VaultRepository

class VaultOperationNotificationCoordinator(
    private val repository: VaultRepository,
    private val notifier: VaultOperationNotifier,
    private val completionDelayMillis: Long = MIN_SUCCESS_NOTIFICATION_VISIBLE_MILLIS,
) {

    suspend fun refresh(operationQueueKey: String, phase: VaultOperationNotificationPhase? = null) {
        val activeJobs = repository.getActiveTransferJobsForOperationQueueKey(operationQueueKey)
        if (activeJobs.isEmpty()) {
            notifier.cancel()
            return
        }

        when (phase) {
            VaultOperationNotificationPhase.Refreshing -> notifier.showRefreshing()
            null -> notifier.showActiveQueue(activeJobs)
        }
    }

    suspend fun finishDrain(operationQueueKey: String, failureCount: Int) {
        delay(completionDelayMillis)
        if (repository.hasActiveTransferJobsForOperationQueueKey(operationQueueKey)) {
            refresh(operationQueueKey)
            return
        }

        if (failureCount > 0) {
            notifier.showFailures(failureCount)
        } else {
            notifier.cancel()
        }
    }

    private fun VaultOperationNotifier.showActiveQueue(activeJobs: List<VaultTransferJob>) {
        val runningJob = activeJobs.firstOrNull { it.state == VaultTransferState.RUNNING }
        if (runningJob != null) {
            showOperationRunning(runningJob.type)
        } else {
            showQueueRunning()
        }
    }

    private companion object {
        const val MIN_SUCCESS_NOTIFICATION_VISIBLE_MILLIS = 1_500L
    }
}

enum class VaultOperationNotificationPhase {
    Refreshing,
}
