package tachiyomi.domain.vault.model

data class ContentVault(
    val id: Long,
    val identity: ContentVaultIdentity,
    val displayName: String,
    val layoutVersion: Long,
    val rootRevision: VaultRevision,
    val writerId: String?,
    val lastCatalogueRefreshAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun create(
            identity: ContentVaultIdentity,
            displayName: String,
            layoutVersion: Long,
            now: Long,
            writerId: String? = null,
        ) = ContentVault(
            id = -1,
            identity = identity,
            displayName = displayName,
            layoutVersion = layoutVersion,
            rootRevision = VaultRevision.initial(),
            writerId = writerId,
            lastCatalogueRefreshAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
