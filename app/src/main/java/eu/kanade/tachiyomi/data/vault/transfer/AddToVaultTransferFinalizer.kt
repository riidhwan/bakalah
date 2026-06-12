package eu.kanade.tachiyomi.data.vault.transfer

import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState

internal object AddToVaultTransferFinalizer {

    fun complete(
        job: VaultTransferJob,
        added: Int,
        replaced: Int,
        failures: List<AddToVaultChapterFailure>,
        completedAt: Long,
    ): VaultTransferJob {
        val state = when {
            added + replaced == 0 -> VaultTransferState.FAILED
            failures.isNotEmpty() -> VaultTransferState.PARTIALLY_SUCCEEDED
            else -> VaultTransferState.SUCCEEDED
        }
        return job.copy(
            state = state,
            failureReason = if (state == VaultTransferState.FAILED) {
                failures.firstOrNull()?.category
            } else {
                null
            },
            addedCount = added.toLong(),
            replacedCount = replaced.toLong(),
            failedCount = failures.size.toLong(),
            detailJson = failures.toDetailJson(),
            updatedAt = completedAt,
            completedAt = completedAt,
        )
    }

    fun cancel(
        job: VaultTransferJob,
        selectedCount: Int,
        added: Int,
        replaced: Int,
        failures: List<AddToVaultChapterFailure>,
        completedAt: Long,
    ): VaultTransferJob {
        val cancelled = selectedCount - added - replaced - failures.size
        return job.copy(
            state = VaultTransferState.CANCELLED,
            addedCount = added.toLong(),
            replacedCount = replaced.toLong(),
            failedCount = failures.size.toLong(),
            cancelledCount = cancelled.toLong(),
            detailJson = failures.toDetailJson(),
            updatedAt = completedAt,
            completedAt = completedAt,
        )
    }

    fun stopAfterGlobalFailure(
        job: VaultTransferJob,
        selectedCount: Int,
        added: Int,
        replaced: Int,
        failures: List<AddToVaultChapterFailure>,
        globalFailure: AddToVaultChapterFailure,
        completedAt: Long,
    ): VaultTransferJob {
        val allFailures = failures + globalFailure
        val cancelled = selectedCount - added - replaced - failures.size - 1
        return job.copy(
            state = if (added + replaced > 0) {
                VaultTransferState.PARTIALLY_SUCCEEDED
            } else {
                VaultTransferState.FAILED
            },
            failureReason = globalFailure.category,
            addedCount = added.toLong(),
            replacedCount = replaced.toLong(),
            failedCount = allFailures.size.toLong(),
            cancelledCount = cancelled.coerceAtLeast(0).toLong(),
            detailJson = allFailures.toDetailJson(),
            updatedAt = completedAt,
            completedAt = completedAt,
        )
    }
}

internal data class AddToVaultChapterFailure(
    val title: String,
    val category: String,
)

internal fun List<AddToVaultChapterFailure>.toDetailJson(): String? {
    if (isEmpty()) return null
    return joinToString(prefix = "[", postfix = "]") {
        """{"title":${it.title.jsonString()},"category":${it.category.jsonString()}}"""
    }
}

private fun String.jsonString(): String {
    return buildString {
        append('"')
        this@jsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
