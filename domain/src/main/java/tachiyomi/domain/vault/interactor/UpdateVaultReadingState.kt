package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.repository.VaultRepository

class UpdateVaultReadingState(
    private val repository: VaultRepository,
) {
    suspend fun await(state: VaultReadingState) = repository.upsertReadingState(state)
}
