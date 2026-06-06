package tachiyomi.domain.vault.model

data class ImportTargetHint(
    val localMangaId: Long,
    val localMangaIdentity: String?,
    val vaultMangaId: Long,
    val updatedAt: Long,
)
