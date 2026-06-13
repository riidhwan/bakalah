package tachiyomi.domain.vault.model

data class VaultChapterThumbnail(
    val id: Long,
    val chapterId: Long,
    val identity: VaultIdentity,
    val path: String,
    val mediaType: String?,
    val sizeBytes: Long?,
    val checksumSha256: String?,
    val revision: VaultRevision,
    val updatedAt: Long,
)
