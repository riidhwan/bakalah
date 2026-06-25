package eu.kanade.tachiyomi.ui.manga.dialog

import eu.kanade.tachiyomi.ui.manga.library.DuplicateMangaGroupTargetItem
import eu.kanade.tachiyomi.ui.manga.library.LibraryMangaGroupCandidateItem
import eu.kanade.tachiyomi.ui.manga.library.PendingAddToGroup
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

sealed interface MangaLibraryDialog {
    data class ChangeCategory(
        val manga: Manga,
        val initialSelection: List<CheckboxState<Category>>,
        val pendingAddToGroup: PendingAddToGroup? = null,
    ) : MangaLibraryDialog

    data class DuplicateManga(
        val manga: Manga,
        val duplicates: List<MangaWithChapterCount>,
        val groupTargets: List<DuplicateMangaGroupTargetItem> = emptyList(),
    ) : MangaLibraryDialog

    data class LibraryMangaGroupSetup(
        val groupId: Long?,
        val initialTitle: String,
        val candidates: List<LibraryMangaGroupCandidateItem>,
    ) : MangaLibraryDialog

    data class SetFetchInterval(val manga: Manga) : MangaLibraryDialog
}
