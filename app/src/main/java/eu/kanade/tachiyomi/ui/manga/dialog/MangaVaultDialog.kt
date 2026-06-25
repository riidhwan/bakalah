package eu.kanade.tachiyomi.ui.manga.dialog

import eu.kanade.tachiyomi.ui.manga.vault.LocalVaultImportTargetSelection
import tachiyomi.domain.vault.model.VaultManga

sealed interface MangaVaultDialog {
    data class LocalVaultTargetSetup(
        val initialTitle: String,
        val targets: List<VaultManga>,
        val selectedTarget: LocalVaultImportTargetSelection?,
        val allowCreateNew: Boolean,
        val allowUnlink: Boolean,
        val pendingAddToVault: Boolean,
    ) : MangaVaultDialog

    data class LocalVaultReplaceChapters(val chapterTitles: List<String>) : MangaVaultDialog
}
