package eu.kanade.presentation.vault

import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import tachiyomi.domain.vault.model.VaultCacheState

internal fun VaultMangaScreenModel.State.primaryActionChapter(): VaultMangaScreenModel.VaultChapterItem? {
    return chapters.firstOrNull { it.state == VaultCacheState.CACHED }
        ?: chapters.firstOrNull { it.state == VaultCacheState.VAULT_ONLY }
}
