package eu.kanade.tachiyomi.ui.manga.library

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.diagnostic.PersistenceDiagnosticRecorder
import eu.kanade.tachiyomi.util.removeCovers
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetSameTitleLibraryManga
import tachiyomi.domain.manga.interactor.ManageLibraryMangaGroup
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import kotlin.coroutines.cancellation.CancellationException

internal class MangaLibraryActionCoordinator(
    private val dependencies: Dependencies,
) {

    suspend fun removeFromLibrary(manga: Manga): Boolean {
        return dependencies.persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_LIBRARY_UPDATE) {
            removeFromLibraryInternal(manga)
        }
    }

    private suspend fun removeFromLibraryInternal(manga: Manga): Boolean {
        if (!updateFavorite(manga.id, false)) return false

        if (manga.removeCovers() != manga) {
            dependencies.updateManga.awaitUpdateCoverLastModified(manga.id)
        }
        return true
    }

    suspend fun addToLibrary(
        manga: Manga,
        checkDuplicate: Boolean,
        pendingAddToGroup: PendingAddToGroup? = null,
    ): AddToLibraryResult {
        return dependencies.persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_LIBRARY_UPDATE) {
            if (checkDuplicate) {
                val duplicates = dependencies.getSameTitleLibraryManga(manga)
                if (duplicates.isNotEmpty()) {
                    return@trace AddToLibraryResult.DuplicateFound(
                        duplicates = duplicates,
                        groupTargets = buildDuplicateMangaGroupTargets(duplicates),
                    )
                }
            }

            addToLibraryWithDefaultCategoryOrPrompt(manga, pendingAddToGroup)
        }
    }

    suspend fun categorySelection(manga: Manga): CategorySelection {
        return CategorySelection(
            categories = getUserCategories(),
            selectedCategoryIds = getMangaCategoryIds(manga),
        )
    }

    suspend fun moveToCategoriesAndAddToLibrary(manga: Manga, categoryIds: List<Long>): Boolean {
        return dependencies.persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_LIBRARY_UPDATE) {
            setCategories(manga.id, categoryIds)
            manga.favorite || updateFavorite(manga.id, true)
        }
    }

    suspend fun addMangaToSelectedGroup(manga: Manga, pendingAddToGroup: PendingAddToGroup): Boolean {
        return dependencies.persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_LIBRARY_UPDATE) {
            try {
                val mutation = buildGroupMutation(manga, pendingAddToGroup) ?: return@trace false
                when (mutation) {
                    is GroupMutation.AddSources -> {
                        dependencies.manageLibraryMangaGroup.addSources(
                            groupId = mutation.groupId,
                            memberMangaIds = mutation.memberMangaIds,
                        )
                    }
                    is GroupMutation.CreateGroup -> {
                        dependencies.manageLibraryMangaGroup.createGroup(
                            primaryMangaId = mutation.primaryMangaId,
                            memberMangaIds = mutation.memberMangaIds,
                        )
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to add manga to selected library group" }
                false
            }
        }
    }

    private suspend fun addToLibraryWithDefaultCategoryOrPrompt(
        manga: Manga,
        pendingAddToGroup: PendingAddToGroup?,
    ): AddToLibraryResult {
        val categories = getUserCategories()
        val defaultCategoryId = dependencies.libraryPreferences.defaultCategory.get().toLong()
        val defaultCategory = categories.find { it.id == defaultCategoryId }
        return when {
            defaultCategory != null -> {
                addToLibraryWithCategories(manga, listOf(defaultCategory.id))
            }
            defaultCategoryId == 0L || categories.isEmpty() -> {
                addToLibraryWithCategories(manga, emptyList())
            }
            else -> {
                AddToLibraryResult.NeedsCategorySelection(
                    selection = CategorySelection(
                        categories = categories,
                        selectedCategoryIds = getMangaCategoryIds(manga),
                    ),
                    pendingAddToGroup = pendingAddToGroup,
                )
            }
        }
    }

    private suspend fun addToLibraryWithCategories(
        manga: Manga,
        categoryIds: List<Long>,
    ): AddToLibraryResult {
        if (!updateFavorite(manga.id, true)) return AddToLibraryResult.NotAdded
        setCategories(manga.id, categoryIds)
        return AddToLibraryResult.Added
    }

    private suspend fun updateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        return dependencies.persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_FAVORITE_WRITE) {
            dependencies.updateManga.awaitUpdateFavorite(mangaId, favorite)
        }
    }

    private suspend fun setCategories(mangaId: Long, categoryIds: List<Long>) {
        dependencies.persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_CATEGORIES_WRITE) {
            dependencies.setMangaCategories.await(mangaId, categoryIds)
        }
    }

    private suspend fun getUserCategories(): List<Category> {
        return dependencies.getCategories.await().filterNot { it.isSystemCategory }
    }

    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return dependencies.getCategories.await(manga.id).map { it.id }
    }

    private fun buildGroupMutation(manga: Manga, pendingAddToGroup: PendingAddToGroup): GroupMutation? {
        val targets = pendingAddToGroup.targets
        val existingGroupId = targets.mapNotNull { it.groupId }.distinct().singleOrNull()
        val existingMangaIds = targets.flatMap { it.memberMangaIds }.distinct()
        val memberMangaIds = existingMangaIds + manga.id
        return when {
            !targets.canAddMangaToGroup(manga.source) -> null
            existingGroupId != null -> GroupMutation.AddSources(
                groupId = existingGroupId,
                memberMangaIds = memberMangaIds,
            )
            existingMangaIds.isNotEmpty() -> GroupMutation.CreateGroup(
                primaryMangaId = existingMangaIds.first(),
                memberMangaIds = memberMangaIds,
            )
            else -> null
        }
    }

    private suspend fun buildDuplicateMangaGroupTargets(
        duplicates: List<MangaWithChapterCount>,
    ): List<DuplicateMangaGroupTargetItem> {
        val groupsByDuplicateMangaId = duplicates.associate { duplicate ->
            duplicate.manga.id to dependencies.manageLibraryMangaGroup.getGroupForManga(duplicate.manga.id)
        }
        return dependencies.libraryMangaGroupStateBuilder.duplicateTargets(
            duplicates = duplicates,
            groupsByDuplicateMangaId = groupsByDuplicateMangaId,
        )
    }

    data class Dependencies(
        val libraryPreferences: LibraryPreferences,
        val getSameTitleLibraryManga: GetSameTitleLibraryManga,
        val getCategories: GetCategories,
        val updateManga: UpdateManga,
        val setMangaCategories: SetMangaCategories,
        val manageLibraryMangaGroup: ManageLibraryMangaGroup,
        val libraryMangaGroupStateBuilder: LibraryMangaGroupStateBuilder,
        val persistenceDiagnostics: PersistenceDiagnosticRecorder,
    )

    private sealed interface GroupMutation {
        data class CreateGroup(
            val primaryMangaId: Long,
            val memberMangaIds: List<Long>,
        ) : GroupMutation

        data class AddSources(
            val groupId: Long,
            val memberMangaIds: List<Long>,
        ) : GroupMutation
    }
}

internal sealed interface AddToLibraryResult {
    data object Added : AddToLibraryResult
    data object NotAdded : AddToLibraryResult
    data class DuplicateFound(
        val duplicates: List<MangaWithChapterCount>,
        val groupTargets: List<DuplicateMangaGroupTargetItem>,
    ) : AddToLibraryResult
    data class NeedsCategorySelection(
        val selection: CategorySelection,
        val pendingAddToGroup: PendingAddToGroup?,
    ) : AddToLibraryResult
}

internal data class CategorySelection(
    val categories: List<Category>,
    val selectedCategoryIds: List<Long>,
)

data class PendingAddToGroup(
    val targets: List<DuplicateMangaGroupTargetItem>,
)
