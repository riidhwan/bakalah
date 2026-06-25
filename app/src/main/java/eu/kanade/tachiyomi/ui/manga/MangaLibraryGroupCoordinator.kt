package eu.kanade.tachiyomi.ui.manga

import tachiyomi.domain.manga.interactor.ManageLibraryMangaGroup
import tachiyomi.domain.manga.model.Manga

internal class MangaLibraryGroupCoordinator(
    private val dependencies: Dependencies,
) {

    suspend fun tabs(selectedMangaId: Long): List<LibraryMangaGroupTab> {
        val group = dependencies.manageLibraryMangaGroup.getGroupForManga(selectedMangaId)
        return dependencies.libraryMangaGroupStateBuilder.tabs(
            group = group,
            selectedMangaId = selectedMangaId,
        )
    }

    suspend fun setup(
        manga: Manga,
        currentTabs: List<LibraryMangaGroupTab>,
    ): LibraryMangaGroupSetupState {
        val groupId = currentTabs.firstOrNull()?.let {
            dependencies.manageLibraryMangaGroup.getGroupForManga(manga.id)?.id
        }
        val candidates = dependencies.manageLibraryMangaGroup
            .getCandidates(anchorMangaId = manga.id, groupId = groupId)
            .let { candidates ->
                dependencies.libraryMangaGroupStateBuilder.candidates(
                    candidates = candidates,
                    excludedMangaId = manga.id,
                )
            }

        return LibraryMangaGroupSetupState(
            groupId = groupId,
            initialTitle = manga.title,
            candidates = candidates,
        )
    }

    suspend fun addSources(
        manga: Manga,
        groupId: Long?,
        selectedMangaIds: List<Long>,
    ): List<LibraryMangaGroupTab>? {
        val memberMangaIds = selectedMangaIds
            .filterNot { it == manga.id }
            .distinct()
        if (memberMangaIds.isEmpty()) return null

        if (groupId == null) {
            dependencies.manageLibraryMangaGroup.createGroup(
                primaryMangaId = manga.id,
                memberMangaIds = memberMangaIds,
            )
        } else {
            dependencies.manageLibraryMangaGroup.addSources(
                groupId = groupId,
                memberMangaIds = memberMangaIds,
            )
        }

        return tabs(manga.id)
    }

    suspend fun setPrimary(manga: Manga): List<LibraryMangaGroupTab>? {
        val group = dependencies.manageLibraryMangaGroup.getGroupForManga(manga.id) ?: return null
        dependencies.manageLibraryMangaGroup.setPrimary(group.id, manga.id)
        return tabs(manga.id)
    }

    data class Dependencies(
        val manageLibraryMangaGroup: ManageLibraryMangaGroup,
        val libraryMangaGroupStateBuilder: LibraryMangaGroupStateBuilder,
    )
}

internal data class LibraryMangaGroupSetupState(
    val groupId: Long?,
    val initialTitle: String,
    val candidates: List<LibraryMangaGroupCandidateItem>,
)
