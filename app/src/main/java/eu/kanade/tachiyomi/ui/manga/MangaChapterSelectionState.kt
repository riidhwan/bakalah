package eu.kanade.tachiyomi.ui.manga

import eu.kanade.core.util.addOrRemove
import tachiyomi.domain.chapter.model.Chapter

internal class MangaChapterSelectionState {
    private val selectedIds = HashSet<Long>()
    private var firstSelectedPosition = -1
    private var lastSelectedPosition = -1

    val isEmpty: Boolean
        get() = selectedIds.isEmpty()

    fun clear() {
        selectedIds.clear()
        resetRange()
    }

    fun contains(chapterId: Long): Boolean {
        return chapterId in selectedIds
    }

    fun selectedChapters(chapters: List<ChapterList.Item>): List<Chapter> {
        return chapters
            .filter { it.id in selectedIds }
            .map { it.chapter }
    }

    fun toggle(
        chapters: List<ChapterList.Item>,
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean,
    ): List<ChapterList.Item> {
        val selectedIndex = chapters.indexOfFirst { it.id == item.chapter.id }
        if (selectedIndex < 0) return chapters

        val selectedItem = chapters[selectedIndex]
        if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) {
            return chapters
        }

        val newChapters = chapters.toMutableList()
        val firstSelection = newChapters.none { it.selected }
        newChapters[selectedIndex] = selectedItem.copy(selected = selected)
        selectedIds.addOrRemove(item.id, selected)

        if (selected && fromLongPress) {
            selectRangeOnLongPress(
                chapters = newChapters,
                selectedIndex = selectedIndex,
                firstSelection = firstSelection,
            )
        } else if (!fromLongPress) {
            updateRangeAfterRegularToggle(
                chapters = newChapters,
                selectedIndex = selectedIndex,
                selected = selected,
            )
        }

        return newChapters
    }

    fun toggleAll(chapters: List<ChapterList.Item>, selected: Boolean): List<ChapterList.Item> {
        val newChapters = chapters.map {
            selectedIds.addOrRemove(it.id, selected)
            it.copy(selected = selected)
        }
        resetRange()
        return newChapters
    }

    fun invert(chapters: List<ChapterList.Item>): List<ChapterList.Item> {
        val newChapters = chapters.map {
            selectedIds.addOrRemove(it.id, !it.selected)
            it.copy(selected = !it.selected)
        }
        resetRange()
        return newChapters
    }

    private fun selectRangeOnLongPress(
        chapters: MutableList<ChapterList.Item>,
        selectedIndex: Int,
        firstSelection: Boolean,
    ) {
        if (firstSelection) {
            firstSelectedPosition = selectedIndex
            lastSelectedPosition = selectedIndex
            return
        }

        val range = when {
            selectedIndex < firstSelectedPosition -> {
                val range = selectedIndex + 1..<firstSelectedPosition
                firstSelectedPosition = selectedIndex
                range
            }
            selectedIndex > lastSelectedPosition -> {
                val range = (lastSelectedPosition + 1)..<selectedIndex
                lastSelectedPosition = selectedIndex
                range
            }
            else -> IntRange.EMPTY
        }

        range.forEach {
            val inbetweenItem = chapters[it]
            if (!inbetweenItem.selected) {
                selectedIds.add(inbetweenItem.id)
                chapters[it] = inbetweenItem.copy(selected = true)
            }
        }
    }

    private fun updateRangeAfterRegularToggle(
        chapters: List<ChapterList.Item>,
        selectedIndex: Int,
        selected: Boolean,
    ) {
        if (!selected) {
            if (selectedIndex == firstSelectedPosition) {
                firstSelectedPosition = chapters.indexOfFirst { it.selected }
            } else if (selectedIndex == lastSelectedPosition) {
                lastSelectedPosition = chapters.indexOfLast { it.selected }
            }
        } else {
            if (selectedIndex < firstSelectedPosition) {
                firstSelectedPosition = selectedIndex
            } else if (selectedIndex > lastSelectedPosition) {
                lastSelectedPosition = selectedIndex
            }
        }
    }

    private fun resetRange() {
        firstSelectedPosition = -1
        lastSelectedPosition = -1
    }
}
