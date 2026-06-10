package tachiyomi.domain.vault.model

data class ImportTargetHint(
    val localMangaId: Long,
    val localMangaIdentity: String?,
    val contentVaultIdentity: ContentVaultIdentity? = null,
    val sourceIdentity: String? = localMangaIdentity,
    val vaultMangaIdentity: VaultIdentity? = null,
    val vaultMangaId: Long,
    val updatedAt: Long,
)
