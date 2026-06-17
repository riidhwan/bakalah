package eu.kanade.tachiyomi.data.vault.publishing

import eu.kanade.tachiyomi.data.vault.remote.childPath
import eu.kanade.tachiyomi.data.vault.remote.webdav.WebDavVaultRemoteStorage
import eu.kanade.tachiyomi.data.vault.transfer.vaultTransferIntegrity
import eu.kanade.tachiyomi.data.vault.webdav.RemoteVaultWebDav
import eu.kanade.tachiyomi.data.vault.webdav.VaultWebDav
import eu.kanade.tachiyomi.network.NetworkHelper
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

interface VaultChapterThumbnailDisplayLoader {
    suspend fun loadLocal(
        manga: VaultManga,
        chapter: VaultChapter,
    ): VaultChapterThumbnailDisplayResult

    suspend fun load(
        manga: VaultManga,
        chapter: VaultChapter,
    ): VaultChapterThumbnailDisplayResult
}

internal class DefaultVaultChapterThumbnailDisplayLoader(
    networkHelper: NetworkHelper,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val cacheStore: VaultChapterThumbnailCacheStore,
    private val webDavFactory: (WebDavVaultConfig) -> VaultWebDav = {
        RemoteVaultWebDav(WebDavVaultRemoteStorage(it, networkHelper.nonCloudflareClient))
    },
) : VaultChapterThumbnailDisplayLoader {

    override suspend fun loadLocal(
        manga: VaultManga,
        chapter: VaultChapter,
    ): VaultChapterThumbnailDisplayResult {
        chapter.thumbnail ?: return VaultChapterThumbnailDisplayResult.Unavailable
        return localThumbnailUri(manga, chapter)
            ?.let(VaultChapterThumbnailDisplayResult::Ready)
            ?: VaultChapterThumbnailDisplayResult.Unavailable
    }

    override suspend fun load(
        manga: VaultManga,
        chapter: VaultChapter,
    ): VaultChapterThumbnailDisplayResult {
        val thumbnail = chapter.thumbnail ?: return VaultChapterThumbnailDisplayResult.Unavailable
        localThumbnailUri(manga, chapter)?.let { return VaultChapterThumbnailDisplayResult.Ready(it) }
        if (hasActiveThumbnailPublish(manga, chapter)) return VaultChapterThumbnailDisplayResult.ActiveTransfer

        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultChapterThumbnailDisplayResult.Unavailable
        return runCatching {
            val bytes = webDavFactory(config).getBytes(config.rootPath.childPath(thumbnail.path))
                ?: return VaultChapterThumbnailDisplayResult.LoadFailed
            val integrity = bytes.vaultTransferIntegrity()
            if (thumbnail.sizeBytes != null && thumbnail.sizeBytes != integrity.sizeBytes) {
                return VaultChapterThumbnailDisplayResult.LoadFailed
            }
            if (thumbnail.checksumSha256 != null && thumbnail.checksumSha256 != integrity.checksumSha256) {
                return VaultChapterThumbnailDisplayResult.LoadFailed
            }
            cacheStore.write(cacheKey(manga, chapter), bytes)
            localThumbnailUri(manga, chapter)
                ?.let(VaultChapterThumbnailDisplayResult::Ready)
                ?: VaultChapterThumbnailDisplayResult.LoadFailed
        }.getOrElse {
            VaultChapterThumbnailDisplayResult.LoadFailed
        }
    }

    private suspend fun hasActiveThumbnailPublish(manga: VaultManga, chapter: VaultChapter): Boolean {
        return repository.getTransferJobsForVault(manga.vaultId).any {
            it.type == VaultTransferType.THUMBNAIL_PUBLISH &&
                it.chapterId == chapter.id &&
                it.state in ACTIVE_TRANSFER_STATES
        }
    }

    private fun localThumbnailUri(manga: VaultManga, chapter: VaultChapter): String? {
        return cacheStore.localUri(cacheKey(manga, chapter))
    }

    private fun cacheKey(manga: VaultManga, chapter: VaultChapter): VaultChapterThumbnailCacheKey {
        val thumbnail = chapter.thumbnail ?: error("Thumbnail unavailable")
        return VaultChapterThumbnailCacheKey(
            vaultId = manga.vaultId,
            mangaIdentity = manga.identity.value,
            chapterIdentity = chapter.identity.value,
            thumbnailIdentity = thumbnail.identity.value,
            remotePath = thumbnail.path,
        )
    }

    private companion object {
        val ACTIVE_TRANSFER_STATES = setOf(VaultTransferState.QUEUED, VaultTransferState.RUNNING)
    }
}

sealed interface VaultChapterThumbnailDisplayResult {
    data class Ready(val localUri: String) : VaultChapterThumbnailDisplayResult
    data object NotImplemented : VaultChapterThumbnailDisplayResult
    data object Unavailable : VaultChapterThumbnailDisplayResult
    data object ActiveTransfer : VaultChapterThumbnailDisplayResult
    data object LoadFailed : VaultChapterThumbnailDisplayResult
}
