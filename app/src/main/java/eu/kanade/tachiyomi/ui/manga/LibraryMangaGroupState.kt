package eu.kanade.tachiyomi.ui.manga

import tachiyomi.domain.manga.model.Manga

data class LibraryMangaGroupTab(
    val mangaId: Long,
    val sourceName: String,
    val selected: Boolean,
    val isPrimary: Boolean,
)

data class LibraryMangaGroupCandidateItem(
    val manga: Manga,
    val sourceName: String,
)

fun List<LibraryMangaGroupTab>.selectManga(mangaId: Long): List<LibraryMangaGroupTab> {
    if (none { it.mangaId == mangaId }) return this
    return map { it.copy(selected = it.mangaId == mangaId) }
}
