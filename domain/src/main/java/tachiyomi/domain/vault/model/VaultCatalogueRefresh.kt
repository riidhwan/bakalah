package tachiyomi.domain.vault.model

data class VaultCatalogueRefresh(
    val vault: ContentVault,
    val labels: List<VaultLabel>,
    val manga: List<VaultCatalogueMangaRefresh>,
    val snapshots: List<VaultManifestSnapshot>,
) {
    val activeManga: List<VaultCatalogueMangaRefresh>
        get() = manga.filter { it.manga.collectionState == VaultMangaCollectionState.ACTIVE }
}

data class VaultCatalogueMangaRefresh(
    val manga: VaultManga,
    val manifestPath: String,
    val labelIdentities: List<VaultIdentity>,
    val cover: VaultCover?,
    val chapters: List<VaultChapter>,
)
