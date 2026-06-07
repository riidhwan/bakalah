package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.repository.VaultRepository

class GetVaultCacheStates(
    private val repository: VaultRepository,
) {
    fun subscribeForManga(mangaId: Long) = repository.getCacheStatesForMangaAsFlow(mangaId)

    fun subscribeForVault(vaultId: Long) = repository.getCacheStatesForVaultAsFlow(vaultId)
}
