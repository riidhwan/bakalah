package eu.kanade.tachiyomi.ui.manga.library

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

data class DuplicateMangaGroupTargetItem(
    val key: String,
    val title: String,
    val sourceName: String,
    val chapterCount: Long,
    val sourceCount: Int,
    val groupId: Long?,
    val memberMangaIds: List<Long>,
    val sourceIds: Set<Long>,
)

fun initialDuplicateMangaGroupTargetSelection(
    targets: List<DuplicateMangaGroupTargetItem>,
): Set<String> {
    return targets
        .singleOrNull()
        ?.key
        ?.let { setOf(it) }
        .orEmpty()
}

fun List<DuplicateMangaGroupTargetItem>.canAddMangaToGroup(
    pendingMangaSourceId: Long,
): Boolean {
    if (isEmpty()) return false
    if (mapNotNull { it.groupId }.distinct().size > 1) return false

    val sourceIds = flatMap { it.sourceIds } + pendingMangaSourceId
    return sourceIds.size == sourceIds.toSet().size
}

fun List<LibraryMangaGroupTab>.selectManga(mangaId: Long): List<LibraryMangaGroupTab> {
    if (none { it.mangaId == mangaId }) return this
    return map { it.copy(selected = it.mangaId == mangaId) }
}
