package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.sameTitleLibraryMatchKey
import tachiyomi.domain.manga.repository.MangaRepository

class GetSameTitleLibraryManga(
    private val mangaRepository: MangaRepository,
) {

    suspend operator fun invoke(manga: Manga): List<MangaWithChapterCount> {
        val titleKey = manga.title.sameTitleLibraryMatchKey()
        if (manga.favorite || manga.source == LOCAL_SOURCE_ID || titleKey.isBlank()) return emptyList()

        return mangaRepository.getLibraryMangaWithChapterCount()
            .filter {
                it.manga.id != manga.id &&
                    it.manga.source != LOCAL_SOURCE_ID &&
                    it.manga.title.sameTitleLibraryMatchKey() == titleKey
            }
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
    }
}
