package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.repository.VaultRepository

class GetVaultManga(
    private val repository: VaultRepository,
) {
    suspend fun await(id: Long) = repository.getMangaById(id)

    suspend fun await(vaultId: Long, identity: VaultIdentity) = repository.getMangaByIdentity(vaultId, identity)

    fun subscribe(vaultId: Long) = repository.getMangaAsFlow(vaultId)
}
