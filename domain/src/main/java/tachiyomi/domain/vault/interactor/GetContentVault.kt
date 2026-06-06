package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.repository.VaultRepository

class GetContentVault(
    private val repository: VaultRepository,
) {
    suspend fun await(identity: ContentVaultIdentity) = repository.getVaultByIdentity(identity)

    fun subscribeAll() = repository.getVaultsAsFlow()
}
