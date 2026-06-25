package eu.kanade.tachiyomi.ui.manga.dialog

import tachiyomi.domain.chapter.model.Chapter

sealed interface MangaChapterDialog {
    data class DeleteChapters(val chapters: List<Chapter>) : MangaChapterDialog
    data object SettingsSheet : MangaChapterDialog
}
