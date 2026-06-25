package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.source.Source
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga

internal class MangaLibraryWorkflowCoordinator(
    private val libraryActionCoordinator: MangaLibraryActionCoordinator,
    private val libraryGroupCoordinator: MangaLibraryGroupCoordinator,
    private val addTracks: AddTracks,
) {

    suspend fun toggleFavorite(
        manga: Manga,
        source: Source,
        isFavorited: Boolean,
        checkDuplicate: Boolean,
    ): MangaLibraryWorkflowEffect {
        return if (isFavorited) {
            if (libraryActionCoordinator.removeFromLibrary(manga)) {
                MangaLibraryWorkflowEffect.Removed
            } else {
                MangaLibraryWorkflowEffect.None
            }
        } else {
            addToLibrary(
                manga = manga,
                source = source,
                checkDuplicate = checkDuplicate,
            )
        }
    }

    suspend fun showChangeCategoryDialog(manga: Manga): MangaLibraryWorkflowEffect {
        val selection = libraryActionCoordinator.categorySelection(manga)
        return MangaLibraryWorkflowEffect.ShowDialog(
            MangaScreenModel.LibraryDialog.ChangeCategory(
                manga = manga,
                initialSelection = selection.toCheckboxState(),
            ),
        )
    }

    suspend fun addDuplicateMangaToGroup(
        manga: Manga,
        source: Source,
        targets: List<DuplicateMangaGroupTargetItem>,
    ): MangaLibraryWorkflowEffect {
        if (targets.isEmpty()) return MangaLibraryWorkflowEffect.None

        val pendingAddToGroup = PendingAddToGroup(targets)
        val effect = addToLibrary(
            manga = manga,
            source = source,
            checkDuplicate = false,
            pendingAddToGroup = pendingAddToGroup,
            bindEnhancedTrackers = false,
            ignoreDuplicateResult = true,
        )
        if (effect !is MangaLibraryWorkflowEffect.Added) return effect

        val tabsEffect = addMangaToSelectedGroup(manga, pendingAddToGroup)
        addTracks.bindEnhancedTrackers(manga, source)
        return tabsEffect
    }

    suspend fun moveMangaToCategoriesAndAddToLibrary(
        manga: Manga,
        source: Source,
        categories: List<Long>,
        pendingAddToGroup: PendingAddToGroup?,
    ): MangaLibraryWorkflowEffect {
        if (!libraryActionCoordinator.moveToCategoriesAndAddToLibrary(manga, categories)) {
            return MangaLibraryWorkflowEffect.None
        }

        if (pendingAddToGroup == null) return MangaLibraryWorkflowEffect.None

        val tabsEffect = addMangaToSelectedGroup(manga, pendingAddToGroup)
        addTracks.bindEnhancedTrackers(manga, source)
        return tabsEffect
    }

    private suspend fun addToLibrary(
        manga: Manga,
        source: Source,
        checkDuplicate: Boolean,
        pendingAddToGroup: PendingAddToGroup? = null,
        bindEnhancedTrackers: Boolean = true,
        ignoreDuplicateResult: Boolean = false,
    ): MangaLibraryWorkflowEffect {
        return when (
            val result = libraryActionCoordinator.addToLibrary(
                manga = manga,
                checkDuplicate = checkDuplicate,
                pendingAddToGroup = pendingAddToGroup,
            )
        ) {
            AddToLibraryResult.Added -> {
                if (bindEnhancedTrackers) {
                    addTracks.bindEnhancedTrackers(manga, source)
                }
                MangaLibraryWorkflowEffect.Added
            }
            AddToLibraryResult.NotAdded -> MangaLibraryWorkflowEffect.None
            is AddToLibraryResult.DuplicateFound -> {
                if (ignoreDuplicateResult) {
                    MangaLibraryWorkflowEffect.None
                } else {
                    MangaLibraryWorkflowEffect.ShowDialog(
                        MangaScreenModel.LibraryDialog.DuplicateManga(
                            manga = manga,
                            duplicates = result.duplicates,
                            groupTargets = result.groupTargets,
                        ),
                    )
                }
            }
            is AddToLibraryResult.NeedsCategorySelection -> {
                MangaLibraryWorkflowEffect.ShowDialog(
                    MangaScreenModel.LibraryDialog.ChangeCategory(
                        manga = manga,
                        initialSelection = result.selection.toCheckboxState(),
                        pendingAddToGroup = result.pendingAddToGroup,
                    ),
                )
            }
        }
    }

    private suspend fun addMangaToSelectedGroup(
        manga: Manga,
        pendingAddToGroup: PendingAddToGroup,
    ): MangaLibraryWorkflowEffect {
        if (!libraryActionCoordinator.addMangaToSelectedGroup(manga, pendingAddToGroup)) {
            return MangaLibraryWorkflowEffect.None
        }

        return MangaLibraryWorkflowEffect.UpdateGroupTabs(
            tabs = libraryGroupCoordinator.tabs(manga.id),
            dismissDialog = true,
        )
    }

    private fun CategorySelection.toCheckboxState(): List<CheckboxState<Category>> {
        return categories.mapAsCheckboxState { category -> category.id in selectedCategoryIds }
    }
}

internal sealed interface MangaLibraryWorkflowEffect {
    data object None : MangaLibraryWorkflowEffect
    data object Added : MangaLibraryWorkflowEffect
    data object Removed : MangaLibraryWorkflowEffect
    data class ShowDialog(val dialog: MangaScreenModel.LibraryDialog) : MangaLibraryWorkflowEffect
    data class UpdateGroupTabs(
        val tabs: List<LibraryMangaGroupTab>,
        val dismissDialog: Boolean,
    ) : MangaLibraryWorkflowEffect
}
