package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class MangaChapterSelectionStateTest {

    private val selectionState = MangaChapterSelectionState()

    @Test
    fun `toggle selects a single chapter`() {
        val chapters = chapterItems(1, 2, 3)
        val result = selectionState.toggle(
            chapters = chapters,
            item = chapters[1],
            selected = true,
            fromLongPress = false,
        )

        result.selectedIds().shouldContainExactly(2)
        selectionState.contains(2) shouldBe true
        selectionState.selectedChapters(result).map { it.id }.shouldContainExactly(2)
    }

    @Test
    fun `long press extends selection range across visible chapters`() {
        val chapters = chapterItems(1, 2, 3, 4, 5)
        val firstResult = selectionState.toggle(
            chapters = chapters,
            item = chapters[1],
            selected = true,
            fromLongPress = true,
        )
        val rangeResult = selectionState.toggle(
            chapters = firstResult,
            item = firstResult[4],
            selected = true,
            fromLongPress = true,
        )

        rangeResult.selectedIds().shouldContainExactly(2, 3, 4, 5)
    }

    @Test
    fun `toggle all selects and clears every chapter`() {
        val chapters = chapterItems(1, 2, 3)
        val selected = selectionState.toggleAll(chapters, selected = true)

        selected.selectedIds().shouldContainExactly(1, 2, 3)
        selectionState.isEmpty shouldBe false

        val cleared = selectionState.toggleAll(selected, selected = false)

        cleared.selectedIds().shouldContainExactly()
        selectionState.isEmpty shouldBe true
    }

    @Test
    fun `invert flips selected chapters and selected ids`() {
        val chapters = chapterItems(1, 2, 3)
        val selected = selectionState.toggle(
            chapters = chapters,
            item = chapters[0],
            selected = true,
            fromLongPress = false,
        )

        val inverted = selectionState.invert(selected)

        inverted.selectedIds().shouldContainExactly(2, 3)
        selectionState.contains(1) shouldBe false
        selectionState.contains(2) shouldBe true
        selectionState.contains(3) shouldBe true
    }

    private fun chapterItems(vararg ids: Long): List<ChapterList.Item> {
        return ids.map { id ->
            ChapterList.Item(
                chapter = Chapter.create().copy(
                    id = id,
                    url = "chapter-$id",
                    name = "Chapter $id",
                ),
                downloadState = Download.State.NOT_DOWNLOADED,
                downloadProgress = 0,
            )
        }
    }

    private fun List<ChapterList.Item>.selectedIds(): List<Long> {
        return filter { it.selected }.map { it.id }
    }
}
