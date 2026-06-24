package eu.kanade.tachiyomi.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DuplicateMangaGroupTargetTest {

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
}
