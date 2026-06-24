package eu.kanade.tachiyomi.ui.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.LibraryMangaGroup
import tachiyomi.domain.manga.model.LibraryMangaGroupCandidate
import tachiyomi.domain.manga.model.LibraryMangaGroupMember
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

class DuplicateMangaGroupTargetTest {

    private val builder = LibraryMangaGroupStateBuilder(
        sourceName = { sourceId -> "Source $sourceId" },
    )

    @Test
    fun `duplicate target uses standalone manga when duplicate is not grouped`() {
        val duplicate = duplicate(mangaId = 1, sourceId = 11, title = "Alpha", chapterCount = 3)

        val targets = builder.duplicateTargets(
            duplicates = listOf(duplicate),
            groupsByDuplicateMangaId = mapOf(1L to null),
        )

        targets shouldBe listOf(
            DuplicateMangaGroupTargetItem(
                key = "manga:1",
                title = "Alpha",
                sourceName = "Source 11",
                chapterCount = 3,
                sourceCount = 1,
                groupId = null,
                memberMangaIds = listOf(1),
                sourceIds = setOf(11),
            ),
        )
    }

    @Test
    fun `duplicate target collapses grouped duplicates once`() {
        val first = duplicate(mangaId = 1, sourceId = 11, title = "Primary", chapterCount = 3)
        val second = duplicate(mangaId = 2, sourceId = 22, title = "Secondary", chapterCount = 4)
        val group = group(
            id = 10,
            members = listOf(
                member(first.manga, isPrimary = true),
                member(second.manga, isPrimary = false),
                member(manga(mangaId = 3, sourceId = 33, title = "Missing Count"), isPrimary = false),
            ),
        )

        val targets = builder.duplicateTargets(
            duplicates = listOf(first, second),
            groupsByDuplicateMangaId = mapOf(1L to group, 2L to group),
        )

        targets shouldBe listOf(
            DuplicateMangaGroupTargetItem(
                key = "group:10",
                title = "Primary",
                sourceName = "Source 11",
                chapterCount = 7,
                sourceCount = 3,
                groupId = 10,
                memberMangaIds = listOf(1, 2, 3),
                sourceIds = setOf(11, 22, 33),
            ),
        )
    }

    @Test
    fun `tabs mark selected and primary manga`() {
        val tabs = builder.tabs(
            group = group(
                id = 10,
                members = listOf(
                    member(manga(mangaId = 1, sourceId = 11), isPrimary = true),
                    member(manga(mangaId = 2, sourceId = 22), isPrimary = false),
                ),
            ),
            selectedMangaId = 2,
        )

        tabs shouldBe listOf(
            LibraryMangaGroupTab(
                mangaId = 1,
                sourceName = "Source 11",
                selected = false,
                isPrimary = true,
            ),
            LibraryMangaGroupTab(
                mangaId = 2,
                sourceName = "Source 22",
                selected = true,
                isPrimary = false,
            ),
        )
    }

    @Test
    fun `candidates exclude active manga and include source names`() {
        val candidates = builder.candidates(
            candidates = listOf(
                LibraryMangaGroupCandidate(manga(mangaId = 1, sourceId = 11)),
                LibraryMangaGroupCandidate(manga(mangaId = 2, sourceId = 22)),
            ),
            excludedMangaId = 1,
        )

        candidates.map { it.manga.id }.shouldContainExactly(2)
        candidates.map { it.sourceName }.shouldContainExactly("Source 22")
    }

    @Test
    fun `single group target is selected by default`() {
        val target = target(key = "manga:1", sourceId = 1)

        initialDuplicateMangaGroupTargetSelection(listOf(target)) shouldBe setOf("manga:1")
    }

    @Test
    fun `multiple group targets are not selected by default`() {
        val targets = listOf(
            target(key = "manga:1", sourceId = 1),
            target(key = "manga:2", sourceId = 2),
        )

        initialDuplicateMangaGroupTargetSelection(targets) shouldBe emptySet()
    }

    @Test
    fun `can add manga when selected targets have unique sources and at most one existing group`() {
        val targets = listOf(
            target(key = "manga:1", sourceId = 1),
            target(key = "manga:2", sourceId = 2),
        )

        targets.canAddMangaToGroup(pendingMangaSourceId = 3) shouldBe true
    }

    @Test
    fun `cannot add manga when selected target has same source`() {
        val targets = listOf(target(key = "manga:1", sourceId = 1))

        targets.canAddMangaToGroup(pendingMangaSourceId = 1) shouldBe false
    }

    @Test
    fun `cannot add manga to multiple existing groups`() {
        val targets = listOf(
            target(key = "group:1", sourceId = 1, groupId = 1),
            target(key = "group:2", sourceId = 2, groupId = 2),
        )

        targets.canAddMangaToGroup(pendingMangaSourceId = 3) shouldBe false
    }

    private fun target(
        key: String,
        sourceId: Long,
        groupId: Long? = null,
    ): DuplicateMangaGroupTargetItem {
        return DuplicateMangaGroupTargetItem(
            key = key,
            title = "Title",
            sourceName = "Source",
            chapterCount = 1,
            sourceCount = 1,
            groupId = groupId,
            memberMangaIds = listOf(1),
            sourceIds = setOf(sourceId),
        )
    }

    private fun duplicate(
        mangaId: Long,
        sourceId: Long,
        title: String = "Title",
        chapterCount: Long = 1,
    ): MangaWithChapterCount {
        return MangaWithChapterCount(
            manga = manga(mangaId = mangaId, sourceId = sourceId, title = title),
            chapterCount = chapterCount,
        )
    }

    private fun manga(
        mangaId: Long,
        sourceId: Long,
        title: String = "Title",
    ): Manga {
        return Manga.create().copy(
            id = mangaId,
            source = sourceId,
            title = title,
        )
    }

    private fun group(
        id: Long,
        members: List<LibraryMangaGroupMember>,
    ): LibraryMangaGroup {
        return LibraryMangaGroup(
            id = id,
            members = members,
        )
    }

    private fun member(
        manga: Manga,
        isPrimary: Boolean,
    ): LibraryMangaGroupMember {
        return LibraryMangaGroupMember(
            manga = manga,
            isPrimary = isPrimary,
        )
    }
}
