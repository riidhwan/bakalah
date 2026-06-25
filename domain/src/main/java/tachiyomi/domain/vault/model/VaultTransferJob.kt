package tachiyomi.domain.vault.model

data class VaultTransferJob(
    val id: Long,
    val vaultId: Long,
    val mangaId: Long? = null,
    val chapterId: Long?,
    val importRequestId: Long? = null,
    val operationKey: String? = null,
    val operationQueueKey: String? = null,
    val payloadJson: String? = null,
    val type: VaultTransferType,
    val state: VaultTransferState,
    val remotePath: String?,
    val localPath: String?,
    val stagedPath: String?,
    val sizeBytes: Long?,
    val checksumSha256: String?,
    val failureReason: String?,
    val addedCount: Long = 0,
    val replacedCount: Long = 0,
    val failedCount: Long = 0,
    val cancelledCount: Long = 0,
    val detailJson: String? = null,
    val attempts: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
) {
    val isTerminal: Boolean
        get() = state == VaultTransferState.SUCCEEDED ||
            state == VaultTransferState.PARTIALLY_SUCCEEDED ||
            state == VaultTransferState.FAILED ||
            state == VaultTransferState.CANCELLED ||
            state == VaultTransferState.INTEGRITY_FAULT
}

enum class VaultTransferType {
    IMPORT_PUBLISH,
    CAPTURE_PUBLISH,
    CATALOGUE_REFRESH,
    METADATA_PUBLISH,
    CACHE_CHAPTER,
    THUMBNAIL_PUBLISH,
    CHAPTER_DELETE,
    CHAPTER_RENAME,
}

enum class VaultTransferState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,
    CANCELLED,
    INTEGRITY_FAULT,
}
