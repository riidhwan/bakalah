package eu.kanade.tachiyomi.ui.manga.source

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.coroutines.cancellation.CancellationException

internal class MangaSourceRefreshCoordinator(
    private val dependencies: Dependencies,
) {

    suspend fun refreshFromSource(
        manga: Manga,
        source: Source,
        refreshManga: Boolean,
        refreshChapters: Boolean,
        manualFetch: Boolean,
    ): MangaSourceRefreshOutcome {
        return coroutineScope {
            val mangaDetails = async {
                if (refreshManga) {
                    fetchMangaFromSource(
                        manga = manga,
                        source = source,
                        manualFetch = manualFetch,
                    )
                } else {
                    null
                }
            }
            val chapters = async {
                if (refreshChapters) {
                    fetchChaptersFromSource(
                        manga = manga,
                        source = source,
                        manualFetch = manualFetch,
                    )
                } else {
                    null
                }
            }
            MangaSourceRefreshOutcome(
                mangaDetails = mangaDetails.await(),
                chapters = chapters.await(),
            )
        }
    }

    private suspend fun fetchMangaFromSource(
        manga: Manga,
        source: Source,
        manualFetch: Boolean,
    ): MangaSourceRefreshResult {
        return runCatching {
            withIOContext {
                val networkManga = source.getMangaDetails(manga.toSManga())
                dependencies.updateManga.awaitUpdateFromSource(manga, networkManga, manualFetch)
            }
        }.fold(
            onSuccess = {
                MangaSourceRefreshResult.Success
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                if (error is HttpException && error.code == HTTP_EARLY_HINTS) {
                    MangaSourceRefreshResult.IgnoredEarlyHints
                } else {
                    MangaSourceRefreshResult.Failed(error)
                }
            },
        )
    }

    private suspend fun fetchChaptersFromSource(
        manga: Manga,
        source: Source,
        manualFetch: Boolean,
    ): ChapterSourceRefreshResult {
        return runCatching {
            withIOContext {
                val chapters = source.getChapterList(manga.toSManga())
                val newChapters = dependencies.syncChaptersWithSource.await(
                    chapters,
                    manga,
                    source,
                    manualFetch,
                )

                if (manualFetch) {
                    dependencies.filterChaptersForDownload.await(manga, newChapters)
                } else {
                    emptyList()
                }
            }
        }.fold(
            onSuccess = { chaptersToDownload ->
                ChapterSourceRefreshResult.Success(chaptersToDownload)
            },
            onFailure = { error ->
                if (error is CancellationException) throw error

                val latestManga = dependencies.mangaRepository.getMangaById(manga.id)
                when (error) {
                    is NoChaptersException -> ChapterSourceRefreshResult.NoChapters(latestManga)
                    else -> ChapterSourceRefreshResult.Failed(error, latestManga)
                }
            },
        )
    }

    data class Dependencies(
        val updateManga: UpdateManga,
        val syncChaptersWithSource: SyncChaptersWithSource,
        val mangaRepository: MangaRepository,
        val filterChaptersForDownload: FilterChaptersForDownload,
    )

    private companion object {
        const val HTTP_EARLY_HINTS = 103
    }
}

internal data class MangaSourceRefreshOutcome(
    val mangaDetails: MangaSourceRefreshResult?,
    val chapters: ChapterSourceRefreshResult?,
)

internal sealed interface MangaSourceRefreshResult {
    data object Success : MangaSourceRefreshResult
    data object IgnoredEarlyHints : MangaSourceRefreshResult
    data class Failed(val error: Throwable) : MangaSourceRefreshResult
}

internal sealed interface ChapterSourceRefreshResult {
    data class Success(val chaptersToDownload: List<Chapter>) : ChapterSourceRefreshResult
    data class NoChapters(val latestManga: Manga) : ChapterSourceRefreshResult
    data class Failed(val error: Throwable, val latestManga: Manga) : ChapterSourceRefreshResult
}
