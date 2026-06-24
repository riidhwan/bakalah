package eu.kanade.tachiyomi.ui.manga

import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.model.applyFilters
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.manga.model.Manga
import kotlin.math.floor

internal object MangaChapterListProcessor {

    fun process(
        chapters: List<ChapterList.Item>,
        manga: Manga,
    ): List<ChapterList.Item> {
        return chapters.applyFilters(manga).toList()
    }

    fun withMissingChapterSeparators(
        processedChapters: List<ChapterList.Item>,
        manga: Manga,
        hideMissingChapters: Boolean,
    ): List<ChapterList> {
        if (hideMissingChapters) return processedChapters

        return processedChapters.insertSeparators { before, after ->
            val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                after to before
            } else {
                before to after
            }
            if (higherChapter == null) return@insertSeparators null

            if (lowerChapter == null) {
                floor(higherChapter.chapter.chapterNumber)
                    .toInt()
                    .minus(1)
                    .coerceAtLeast(0)
            } else {
                calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
            }
                .takeIf { it > 0 }
                ?.let { missingCount ->
                    ChapterList.MissingCount(
                        id = "${lowerChapter?.id}-${higherChapter.id}",
                        count = missingCount,
                    )
                }
        }
    }
}
