package tachiyomi.domain.vault.model

data class VaultManifestSnapshot(
    val id: Long,
    val vaultId: Long,
    val mangaId: Long?,
    val manifestPath: String,
    val revision: VaultRevision,
    val body: String,
    val fetchedAt: Long,
)
