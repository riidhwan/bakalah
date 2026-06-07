package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.repository.VaultRepository

class CheckVaultRevision(
    private val repository: VaultRepository,
) {
    suspend fun await(
        identity: ContentVaultIdentity,
        expectedRevision: VaultRevision,
    ): VaultRevisionCheckResult {
        val vault = repository.getVaultByIdentity(identity) ?: return VaultRevisionCheckResult.VaultNotFound
        return if (vault.rootRevision == expectedRevision) {
            VaultRevisionCheckResult.Matches
        } else {
            VaultRevisionCheckResult.Mismatch(currentRevision = vault.rootRevision)
        }
    }
}

sealed interface VaultRevisionCheckResult {
    data object Matches : VaultRevisionCheckResult
    data object VaultNotFound : VaultRevisionCheckResult
    data class Mismatch(val currentRevision: VaultRevision) : VaultRevisionCheckResult
}
