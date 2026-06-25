package eu.kanade.tachiyomi.ui.manga

import tachiyomi.domain.manga.model.Manga

data class MangaDialogState(
    val library: MangaLibraryDialog? = null,
    val chapter: MangaChapterDialog? = null,
    val vault: MangaVaultDialog? = null,
    val local: MangaLocalDialog? = null,
    val migration: MangaMigrationDialog? = null,
    val tracking: MangaTrackingDialog? = null,
    val cover: MangaCoverDialogState? = null,
) {
    fun dismissed(): MangaDialogState {
        return MangaDialogState()
    }

    fun withLibrary(dialog: MangaLibraryDialog): MangaDialogState {
        return dismissed().copy(library = dialog)
    }

    fun withChapter(dialog: MangaChapterDialog): MangaDialogState {
        return dismissed().copy(chapter = dialog)
    }

    fun withVault(dialog: MangaVaultDialog): MangaDialogState {
        return dismissed().copy(vault = dialog)
    }

    fun withLocal(dialog: MangaLocalDialog): MangaDialogState {
        return dismissed().copy(local = dialog)
    }

    fun withMigration(dialog: MangaMigrationDialog): MangaDialogState {
        return dismissed().copy(migration = dialog)
    }

    fun withTracking(dialog: MangaTrackingDialog): MangaDialogState {
        return dismissed().copy(tracking = dialog)
    }

    fun withCover(dialog: MangaCoverDialogState): MangaDialogState {
        return dismissed().copy(cover = dialog)
    }
}

sealed interface MangaLocalDialog {
    data class DeleteLocalManga(val manga: Manga) : MangaLocalDialog
}

sealed interface MangaMigrationDialog {
    data class Migrate(val target: Manga, val current: Manga) : MangaMigrationDialog
}

sealed interface MangaTrackingDialog {
    data object TrackSheet : MangaTrackingDialog
}

sealed interface MangaCoverDialogState {
    data object FullCover : MangaCoverDialogState
}
