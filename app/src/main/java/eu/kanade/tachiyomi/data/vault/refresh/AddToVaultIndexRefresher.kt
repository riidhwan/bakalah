package eu.kanade.tachiyomi.data.vault.refresh

import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.repository.VaultRepository

internal class AddToVaultIndexRefresher(
    private val repository: VaultRepository,
    private val refreshService: VaultCatalogueRefresher,
) {
    suspend fun refreshPublishedMangaId(
        vaultIdentity: ContentVaultIdentity,
        mangaIdentity: String,
    ): Long? {
        refreshService.refreshConfiguredVault()
        return repository.getVaultByIdentity(vaultIdentity)
            ?.let { repository.getManga(it.id) }
            ?.firstOrNull { it.identity.value == mangaIdentity }
            ?.id
    }
}
