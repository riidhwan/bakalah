package eu.kanade.presentation.vault

import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel

internal fun VaultMangaScreenModel.State.primaryActionChapter(): VaultMangaScreenModel.VaultChapterItem? {
    return chapters
        .filter { it.readingState?.lastReadAt != null }
        .maxByOrNull { it.readingState?.lastReadAt ?: Long.MIN_VALUE }
        ?: chapters.minByOrNull { it.chapter.sourceOrder }
}
