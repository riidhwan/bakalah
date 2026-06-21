package eu.kanade.tachiyomi.data.vault.operation

import kotlinx.coroutines.delay
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.repository.VaultRepository

internal interface VaultOperationQueueDrainer {
    suspend fun waitUntilDrained(identity: ContentVaultIdentity)
}

internal class VaultOperationQueueDrainService(
    private val repository: VaultRepository,
    private val wakeup: VaultOperationQueueWakeup,
    private val pollDelayMillis: Long = DEFAULT_POLL_DELAY_MILLIS,
) : VaultOperationQueueDrainer {

    override suspend fun waitUntilDrained(identity: ContentVaultIdentity) {
        val operationQueueKey = VaultOperationQueueKey.forContentVault(identity)
        wakeup.wakeOperationQueue(operationQueueKey)
        while (repository.hasActiveTransferJobsForOperationQueueKey(operationQueueKey)) {
            delay(pollDelayMillis)
            wakeup.wakeOperationQueue(operationQueueKey)
        }
    }

    private companion object {
        const val DEFAULT_POLL_DELAY_MILLIS = 500L
    }
}
