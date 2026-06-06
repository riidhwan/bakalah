package tachiyomi.domain.vault.model

data class VaultChapterCacheState(
    val chapterId: Long,
    val state: VaultCacheState,
    val localPath: String?,
    val sizeBytes: Long?,
    val checksumSha256: String?,
    val lastVerifiedAt: Long?,
    val lastOpenedAt: Long?,
    val updatedAt: Long,
    val failureReason: String?,
) {
    val isReadable: Boolean
        get() = state == VaultCacheState.CACHED && localPath != null
}

enum class VaultCacheState {
    VAULT_ONLY,
    QUEUED,
    CACHING,
    PUBLISHING,
    CACHED,
    FAILED,
    INTEGRITY_FAULT,
}
