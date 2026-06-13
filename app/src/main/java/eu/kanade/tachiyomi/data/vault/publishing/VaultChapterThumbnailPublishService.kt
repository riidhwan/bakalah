package eu.kanade.tachiyomi.data.vault.publishing

import tachiyomi.domain.vault.model.VaultIdentity

interface VaultChapterThumbnailPublishService {
    suspend fun publish(request: VaultChapterThumbnailPublishRequest): VaultChapterThumbnailPublishResult
}

class PlaceholderVaultChapterThumbnailPublishService : VaultChapterThumbnailPublishService {
    override suspend fun publish(request: VaultChapterThumbnailPublishRequest): VaultChapterThumbnailPublishResult {
        return VaultChapterThumbnailPublishResult.NotImplemented
    }
}

data class VaultChapterThumbnailPublishRequest(
    val mangaId: Long,
    val chapterId: Long,
    val chapterIdentity: VaultIdentity,
    val sourcePageNumber: Int,
    val crop: VaultChapterThumbnailCrop? = null,
)

data class VaultChapterThumbnailCrop(
    val left: Int,
    val top: Int,
    val size: Int,
)

sealed interface VaultChapterThumbnailPublishResult {
    data object Published : VaultChapterThumbnailPublishResult
    data object NotImplemented : VaultChapterThumbnailPublishResult
    data object Unavailable : VaultChapterThumbnailPublishResult
    data object ActiveTransfer : VaultChapterThumbnailPublishResult
    data object PublishFailed : VaultChapterThumbnailPublishResult
}
