package tachiyomi.source.local

internal const val LOCAL_MANGA_PAGE_SIZE = 25

internal data class LocalMangaPage<T>(
    val items: List<T>,
    val hasNextPage: Boolean,
)

internal fun <T> List<T>.toLocalMangaPage(
    page: Int,
    pageSize: Int = LOCAL_MANGA_PAGE_SIZE,
): LocalMangaPage<T> {
    require(pageSize > 0) { "pageSize must be greater than 0" }

    val pageIndex = page.coerceAtLeast(1) - 1
    val fromIndex = pageIndex * pageSize
    if (fromIndex >= size) {
        return LocalMangaPage(emptyList(), hasNextPage = false)
    }

    val toIndex = minOf(fromIndex + pageSize, size)
    return LocalMangaPage(
        items = subList(fromIndex, toIndex),
        hasNextPage = toIndex < size,
    )
}
