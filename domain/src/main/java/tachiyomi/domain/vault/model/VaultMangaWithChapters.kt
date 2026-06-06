package tachiyomi.domain.vault.model

data class VaultMangaWithChapters(
    val manga: VaultManga,
    val chapters: List<VaultChapter>,
)
