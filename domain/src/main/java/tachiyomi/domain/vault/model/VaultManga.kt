package tachiyomi.domain.vault.model

data class VaultManga(
    val id: Long,
    val vaultId: Long,
    val identity: VaultIdentity,
    val metadata: VaultMetadata,
    val sortKey: String,
    val coverId: Long?,
    val revision: VaultRevision,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun create(
            vaultId: Long,
            identity: VaultIdentity,
            metadata: VaultMetadata,
            revision: VaultRevision,
            now: Long,
        ) = VaultManga(
            id = -1,
            vaultId = vaultId,
            identity = identity,
            metadata = metadata,
            sortKey = metadata.normalizedTitle,
            coverId = null,
            revision = revision,
            createdAt = now,
            updatedAt = now,
        )
    }
}
