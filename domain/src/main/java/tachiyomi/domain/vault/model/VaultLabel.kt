package tachiyomi.domain.vault.model

data class VaultLabel(
    val id: Long,
    val vaultId: Long,
    val identity: VaultIdentity,
    val name: String,
    val sortKey: String,
    val isSensitive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
