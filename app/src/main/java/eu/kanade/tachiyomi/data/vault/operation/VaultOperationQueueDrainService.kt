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
        while (repository.hasActiveTransferJobsForOperationQueueKey(operationQueueKey)) {
            wakeup.wakeOperationQueue(operationQueueKey)
            delay(pollDelayMillis)
        }
    }

    private companion object {
        const val DEFAULT_POLL_DELAY_MILLIS = 500L
    }
}
