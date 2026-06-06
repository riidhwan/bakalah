package tachiyomi.domain.vault.model

data class VaultCover(
    val id: Long,
    val mangaId: Long,
    val identity: VaultIdentity,
    val path: String,
    val mediaType: String?,
    val sizeBytes: Long?,
    val checksumSha256: String?,
    val revision: VaultRevision,
    val updatedAt: Long,
)
