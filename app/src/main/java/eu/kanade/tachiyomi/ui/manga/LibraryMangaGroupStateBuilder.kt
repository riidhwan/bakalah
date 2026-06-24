package eu.kanade.tachiyomi.ui.manga

import tachiyomi.domain.manga.model.LibraryMangaGroup
import tachiyomi.domain.manga.model.LibraryMangaGroupCandidate
import tachiyomi.domain.manga.model.MangaWithChapterCount

class LibraryMangaGroupStateBuilder(
    private val sourceName: (Long) -> String,
) {

    fun duplicateTargets(
        duplicates: List<MangaWithChapterCount>,
        groupsByDuplicateMangaId: Map<Long, LibraryMangaGroup?>,
    ): List<DuplicateMangaGroupTargetItem> {
        val duplicatesByMangaId = duplicates.associateBy { it.manga.id }
        val targets = mutableListOf<DuplicateMangaGroupTargetItem>()
        val addedGroupIds = mutableSetOf<Long>()

        duplicates.forEach { duplicate ->
            val group = groupsByDuplicateMangaId[duplicate.manga.id]
            if (group == null) {
                targets += DuplicateMangaGroupTargetItem(
                    key = "manga:${duplicate.manga.id}",
                    title = duplicate.manga.title,
                    sourceName = sourceName(duplicate.manga.source),
                    chapterCount = duplicate.chapterCount,
                    sourceCount = 1,
                    groupId = null,
                    memberMangaIds = listOf(duplicate.manga.id),
                    sourceIds = setOf(duplicate.manga.source),
                )
                return@forEach
            }

            if (!addedGroupIds.add(group.id)) return@forEach

            val primary = group.primary ?: group.members.firstOrNull() ?: return@forEach
            targets += DuplicateMangaGroupTargetItem(
                key = "group:${group.id}",
                title = primary.manga.title,
                sourceName = sourceName(primary.manga.source),
                chapterCount = group.members.sumOf { member ->
                    duplicatesByMangaId[member.manga.id]?.chapterCount ?: 0L
                },
                sourceCount = group.members.size,
                groupId = group.id,
                memberMangaIds = group.memberMangaIds,
                sourceIds = group.members.map { it.manga.source }.toSet(),
            )
        }

        return targets
    }

    fun candidates(
        candidates: List<LibraryMangaGroupCandidate>,
        excludedMangaId: Long,
    ): List<LibraryMangaGroupCandidateItem> {
        return candidates
            .filterNot { it.manga.id == excludedMangaId }
            .map {
                LibraryMangaGroupCandidateItem(
                    manga = it.manga,
                    sourceName = sourceName(it.manga.source),
                )
            }
    }

    fun tabs(
        group: LibraryMangaGroup?,
        selectedMangaId: Long,
    ): List<LibraryMangaGroupTab> {
        return group
            ?.members
            ?.map { member ->
                LibraryMangaGroupTab(
                    mangaId = member.manga.id,
                    sourceName = sourceName(member.manga.source),
                    selected = member.manga.id == selectedMangaId,
                    isPrimary = member.isPrimary,
                )
            }
            .orEmpty()
    }
}
