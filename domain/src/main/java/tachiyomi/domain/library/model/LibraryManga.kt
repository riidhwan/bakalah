package tachiyomi.domain.library.model

import tachiyomi.domain.manga.model.Manga

data class LibraryManga(
    val manga: Manga,
    val categories: List<Long>,
    val totalChapters: Long,
    val readCount: Long,
    val bookmarkCount: Long,
    val latestUpload: Long,
    val chapterFetchedAt: Long,
    val lastRead: Long,
    val group: LibraryMangaGroupInfo? = null,
) {
    val id: Long = manga.id

    val unreadCount
        get() = if (group != null) 0 else totalChapters - readCount

    val hasBookmarks
        get() = bookmarkCount > 0

    val hasStarted = readCount > 0
}

data class LibraryMangaGroupInfo(
    val id: Long,
    val memberMangaIds: List<Long>,
    val memberSourceIds: List<Long>,
    val memberTitles: List<String>,
    val memberAuthors: List<String>,
    val memberArtists: List<String>,
) {
    val isGrouped: Boolean = memberMangaIds.size > 1
}
