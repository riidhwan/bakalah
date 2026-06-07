package tachiyomi.domain.vault.model

data class VaultCatalogueRefresh(
    val vault: ContentVault,
    val labels: List<VaultLabel>,
    val manga: List<VaultCatalogueMangaRefresh>,
    val snapshots: List<VaultManifestSnapshot>,
)

data class VaultCatalogueMangaRefresh(
    val manga: VaultManga,
    val manifestPath: String,
    val labelIdentities: List<VaultIdentity>,
    val cover: VaultCover?,
    val chapters: List<VaultChapter>,
)
