package eu.kanade.tachiyomi.data.vault.operation

import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.repository.VaultRepository

class VaultOperationQueueDrainServiceTest {

    @Test
    fun `waitUntilDrained wakes worker and polls until queue is inactive`() = runTest {
        val identity = ContentVaultIdentity("vault-a")
        val queueKey = VaultOperationQueueKey.forContentVault(identity)
        val repository = mockk<VaultRepository>()
        coEvery {
            repository.hasActiveTransferJobsForOperationQueueKey(queueKey)
        } returnsMany listOf(true, true, false)
        val wakeup = FakeQueueWakeup()
        val service = VaultOperationQueueDrainService(
            repository = repository,
            wakeup = wakeup,
            pollDelayMillis = 1,
        )

        service.waitUntilDrained(identity)

        wakeup.queueKeys shouldContainExactly listOf(queueKey, queueKey)
    }

    @Test
    fun `waitUntilDrained does not wake worker when queue is already inactive`() = runTest {
        val identity = ContentVaultIdentity("vault-a")
        val queueKey = VaultOperationQueueKey.forContentVault(identity)
        val repository = mockk<VaultRepository>()
        coEvery {
            repository.hasActiveTransferJobsForOperationQueueKey(queueKey)
        } returns false
        val wakeup = FakeQueueWakeup()
        val service = VaultOperationQueueDrainService(
            repository = repository,
            wakeup = wakeup,
            pollDelayMillis = 1,
        )

        service.waitUntilDrained(identity)

        wakeup.queueKeys shouldContainExactly emptyList()
    }

    private class FakeQueueWakeup : VaultOperationQueueWakeup {
        val queueKeys = mutableListOf<String>()

        override fun wakeOperationQueue(operationQueueKey: String) {
            queueKeys += operationQueueKey
        }
    }
}
