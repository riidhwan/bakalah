package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.awaitSingle
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface Source {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        return getMangaUpdate(
            manga = manga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return getMangaUpdate(
            manga = manga,
            chapters = emptyList(),
            fetchDetails = false,
            fetchChapters = true,
        ).chapters
    }

    /**
     * Fetches updated information for a manga.
     *
     * Depending on the provided flags or source availability, this may include updated manga
     * metadata, available chapters, or both.
     *
     * @since tachiyomix 1.6
     * @param manga the manga to fetch updates for.
     * @param chapters existing chapters of the manga.
     * @param fetchDetails whether to fetch updated manga details.
     * @param fetchChapters whether to fetch available chapters.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) fetchMangaDetails(manga).awaitSingle() else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(manga).awaitSingle() else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).awaitSingle()
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaDetails"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getChapterList"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")
}
