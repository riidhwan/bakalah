package tachiyomi.domain.vault.model

data class VaultTransferJob(
    val id: Long,
    val vaultId: Long,
    val chapterId: Long?,
    val type: VaultTransferType,
    val state: VaultTransferState,
    val remotePath: String?,
    val localPath: String?,
    val stagedPath: String?,
    val sizeBytes: Long?,
    val checksumSha256: String?,
    val failureReason: String?,
    val attempts: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
) {
    val isTerminal: Boolean
        get() = state == VaultTransferState.SUCCEEDED ||
            state == VaultTransferState.FAILED ||
            state == VaultTransferState.CANCELLED ||
            state == VaultTransferState.INTEGRITY_FAULT
}

enum class VaultTransferType {
    IMPORT_PUBLISH,
    CATALOGUE_REFRESH,
    METADATA_PUBLISH,
    CACHE_CHAPTER,
}

enum class VaultTransferState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTEGRITY_FAULT,
}
