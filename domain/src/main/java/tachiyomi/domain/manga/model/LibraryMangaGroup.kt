package tachiyomi.domain.manga.model

data class LibraryMangaGroup(
    val id: Long,
    val members: List<LibraryMangaGroupMember>,
) {
    val primary: LibraryMangaGroupMember?
        get() = members.firstOrNull { it.isPrimary }

    val memberMangaIds: List<Long>
        get() = members.map { it.manga.id }
}

data class LibraryMangaGroupMember(
    val manga: Manga,
    val isPrimary: Boolean,
)

data class LibraryMangaGroupCandidate(
    val manga: Manga,
)
