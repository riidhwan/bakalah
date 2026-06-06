package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.repository.VaultRepository

class GetVaultChapters(
    private val repository: VaultRepository,
) {
    fun subscribe(mangaId: Long) = repository.getChaptersAsFlow(mangaId)
}
