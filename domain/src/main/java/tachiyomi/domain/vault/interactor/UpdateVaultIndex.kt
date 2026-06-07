package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.VaultCatalogueRefresh
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.repository.VaultRepository

class UpdateVaultIndex(
    private val repository: VaultRepository,
) {
    suspend fun upsertVault(vault: ContentVault): Long = repository.upsertVault(vault)

    suspend fun upsertManga(manga: VaultManga): Long = repository.upsertManga(manga)

    suspend fun replaceChapters(mangaId: Long, chapters: List<VaultChapter>) {
        repository.upsertChapters(mangaId, chapters)
    }

    suspend fun refreshCatalogue(refresh: VaultCatalogueRefresh): Long {
        return repository.refreshCatalogue(refresh)
    }
}
