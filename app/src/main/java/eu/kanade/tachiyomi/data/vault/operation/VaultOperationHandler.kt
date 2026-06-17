package eu.kanade.tachiyomi.data.vault.operation

import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType

enum class VaultOperationPolicy {
    OptimisticBackgroundPublish,
    BlockingForeground,
    VisibleBackground,
    FireAndReport,
}

interface VaultOperationHandler {
    val type: VaultTransferType
    val policy: VaultOperationPolicy

    suspend fun execute(payloadJson: String): VaultOperationExecutionResult
}

data class VaultOperationExecutionResult(
    val state: VaultTransferState,
    val failureReason: String? = null,
)
