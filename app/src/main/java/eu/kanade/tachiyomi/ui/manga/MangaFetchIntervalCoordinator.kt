package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

internal class MangaFetchIntervalCoordinator(
    private val dependencies: Dependencies,
) {

    suspend fun setFetchInterval(manga: Manga, interval: Int): Manga? {
        val updated = dependencies.updateManga.awaitUpdateFetchInterval(
            // Custom intervals are negative.
            manga.copy(fetchInterval = -interval),
        )
        return if (updated) {
            dependencies.mangaRepository.getMangaById(manga.id)
        } else {
            null
        }
    }

    data class Dependencies(
        val updateManga: UpdateManga,
        val mangaRepository: MangaRepository,
    )
}
