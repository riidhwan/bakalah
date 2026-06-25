package eu.kanade.tachiyomi.ui.manga

internal class MangaChapterListStateReducer(
    private val selection: MangaChapterSelectionState,
) {

    fun applyLocalVaultImportState(
        state: MangaScreenModel.State.Success,
        localVaultImport: LocalVaultImportState,
    ): MangaScreenModel.State.Success {
        return state.copy(
            chapters = state.chapters.map {
                it.copy(importDuplicate = it.chapter.url in localVaultImport.duplicateChapterSelectionIds)
            },
            localVaultImport = localVaultImport,
        )
    }

    fun toggleSelection(
        state: MangaScreenModel.State.Success,
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean,
    ): MangaScreenModel.State.Success {
        return state.copy(
            chapters = selection.toggle(
                chapters = state.processedChapters,
                item = item,
                selected = selected,
                fromLongPress = fromLongPress,
            ),
        )
    }

    fun toggleAllSelection(
        state: MangaScreenModel.State.Success,
        selected: Boolean,
    ): MangaScreenModel.State.Success {
        return state.copy(chapters = selection.toggleAll(state.chapters, selected))
    }

    fun invertSelection(state: MangaScreenModel.State.Success): MangaScreenModel.State.Success {
        return state.copy(chapters = selection.invert(state.chapters))
    }
}
