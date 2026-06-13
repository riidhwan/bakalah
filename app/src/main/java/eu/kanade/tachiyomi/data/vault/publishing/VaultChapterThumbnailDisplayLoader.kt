package eu.kanade.tachiyomi.data.vault.publishing

import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultManga

interface VaultChapterThumbnailDisplayLoader {
    suspend fun load(
        manga: VaultManga,
        chapter: VaultChapter,
    ): VaultChapterThumbnailDisplayResult
}

class PlaceholderVaultChapterThumbnailDisplayLoader : VaultChapterThumbnailDisplayLoader {

    override suspend fun load(
        manga: VaultManga,
        chapter: VaultChapter,
    ): VaultChapterThumbnailDisplayResult {
        return VaultChapterThumbnailDisplayResult.NotImplemented
    }
}

sealed interface VaultChapterThumbnailDisplayResult {
    data class Ready(val localUri: String) : VaultChapterThumbnailDisplayResult
    data object NotImplemented : VaultChapterThumbnailDisplayResult
    data object Unavailable : VaultChapterThumbnailDisplayResult
    data object ActiveTransfer : VaultChapterThumbnailDisplayResult
    data object LoadFailed : VaultChapterThumbnailDisplayResult
}
