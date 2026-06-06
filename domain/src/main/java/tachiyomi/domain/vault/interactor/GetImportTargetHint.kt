package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.repository.VaultRepository

class GetImportTargetHint(
    private val repository: VaultRepository,
) {
    suspend fun await(localMangaId: Long) = repository.getImportTargetHint(localMangaId)
}
