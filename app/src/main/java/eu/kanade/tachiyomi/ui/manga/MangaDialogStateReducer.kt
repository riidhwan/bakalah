package eu.kanade.tachiyomi.ui.manga

internal class MangaDialogStateReducer {

    fun dismiss(state: MangaScreenModel.State.Success): MangaScreenModel.State.Success {
        return state.copy(dialogs = state.dialogs.dismissed())
    }

    fun showLibrary(
        state: MangaScreenModel.State.Success,
        dialog: MangaLibraryDialog,
    ): MangaScreenModel.State.Success {
        return state.copy(dialogs = state.dialogs.withLibrary(dialog))
    }

    fun showChapter(
        state: MangaScreenModel.State.Success,
        dialog: MangaChapterDialog,
    ): MangaScreenModel.State.Success {
        return state.copy(dialogs = state.dialogs.withChapter(dialog))
    }

    fun showVault(
        state: MangaScreenModel.State.Success,
        dialog: MangaVaultDialog,
    ): MangaScreenModel.State.Success {
        return state.copy(dialogs = state.dialogs.withVault(dialog))
    }

    fun showLocal(
        state: MangaScreenModel.State.Success,
        dialog: MangaLocalDialog,
    ): MangaScreenModel.State.Success {
        return state.copy(dialogs = state.dialogs.withLocal(dialog))
    }

    fun showMigration(
        state: MangaScreenModel.State.Success,
        dialog: MangaMigrationDialog,
    ): MangaScreenModel.State.Success {
        return state.copy(dialogs = state.dialogs.withMigration(dialog))
    }

    fun showTracking(
        state: MangaScreenModel.State.Success,
        dialog: MangaTrackingDialog,
    ): MangaScreenModel.State.Success {
        return state.copy(dialogs = state.dialogs.withTracking(dialog))
    }

    fun showCover(
        state: MangaScreenModel.State.Success,
        dialog: MangaCoverDialogState,
    ): MangaScreenModel.State.Success {
        return state.copy(dialogs = state.dialogs.withCover(dialog))
    }
}
