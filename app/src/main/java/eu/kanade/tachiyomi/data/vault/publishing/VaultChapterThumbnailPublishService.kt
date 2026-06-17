package eu.kanade.tachiyomi.data.vault.publishing

import eu.kanade.tachiyomi.data.vault.localimport.childPath
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshResult
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import eu.kanade.tachiyomi.data.vault.remote.webdav.WebDavVaultRemoteStorage
import eu.kanade.tachiyomi.data.vault.transfer.vaultTransferIntegrity
import eu.kanade.tachiyomi.data.vault.webdav.RemoteVaultWebDav
import eu.kanade.tachiyomi.data.vault.webdav.VaultWebDav
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManifestChapterThumbnail
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.util.UUID

interface VaultChapterThumbnailPublishService {
    suspend fun publish(request: VaultChapterThumbnailPublishRequest): VaultChapterThumbnailPublishResult
}

internal class DefaultVaultChapterThumbnailPublishService(
    networkHelper: NetworkHelper,
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val refreshService: VaultCatalogueRefresher,
    private val cacheStore: VaultChapterThumbnailCacheStore,
    private val webDavFactory: (WebDavVaultConfig) -> VaultWebDav = {
        RemoteVaultWebDav(WebDavVaultRemoteStorage(it, networkHelper.nonCloudflareClient))
    },
    private val identityFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) : VaultChapterThumbnailPublishService {
    private val codec = VaultManifestCodec(json)

    override suspend fun publish(request: VaultChapterThumbnailPublishRequest): VaultChapterThumbnailPublishResult {
        if (request.jpegBytes.isEmpty()) return VaultChapterThumbnailPublishResult.Unavailable
        val manga = repository.getMangaById(request.mangaId) ?: return VaultChapterThumbnailPublishResult.Unavailable
        val chapter = repository.getChapters(manga.id).firstOrNull {
            it.id == request.chapterId && it.identity == request.chapterIdentity
        } ?: return VaultChapterThumbnailPublishResult.Unavailable
        if (hasActiveTransfer(manga.id, manga.vaultId, manga.identity.value)) {
            return VaultChapterThumbnailPublishResult.ActiveTransfer
        }

        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultChapterThumbnailPublishResult.Unavailable

        val timestamp = now()
        val jobId = repository.upsertTransferJob(
            VaultTransferJob(
                id = -1,
                vaultId = manga.vaultId,
                chapterId = chapter.id,
                type = VaultTransferType.THUMBNAIL_PUBLISH,
                state = VaultTransferState.QUEUED,
                remotePath = null,
                localPath = null,
                stagedPath = null,
                sizeBytes = request.jpegBytes.size.toLong(),
                checksumSha256 = request.jpegBytes.vaultTransferIntegrity().checksumSha256,
                failureReason = null,
                attempts = 0,
                createdAt = timestamp,
                updatedAt = timestamp,
                startedAt = null,
                completedAt = null,
            ),
        )

        return runCatching {
            markJobRunning(jobId)
            publishRemote(
                config = config,
                mangaIdentity = manga.identity.value,
                chapterIdentity = chapter.identity.value,
                bytes = request.jpegBytes,
            )
        }.fold(
            onSuccess = { published ->
                if (published) {
                    markJobSucceeded(jobId)
                    VaultChapterThumbnailPublishResult.Published
                } else {
                    markJobFailed(jobId, "thumbnail publish failed")
                    VaultChapterThumbnailPublishResult.PublishFailed
                }
            },
            onFailure = { error ->
                markJobFailed(jobId, error.message ?: "thumbnail publish failed")
                VaultChapterThumbnailPublishResult.PublishFailed
            },
        )
    }

    private suspend fun publishRemote(
        config: WebDavVaultConfig,
        mangaIdentity: String,
        chapterIdentity: String,
        bytes: ByteArray,
    ): Boolean {
        val storage = webDavFactory(config)
        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootBody = storage.get(rootPath) ?: return false
        val root = when (val result = codec.decodeRoot(rootBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            else -> return false
        }
        val expectedIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (expectedIdentity != null && expectedIdentity != root.identity) return false
        val localVault = repository.getVaultByIdentity(ContentVaultIdentity(root.identity)) ?: return false
        val remoteRevision = VaultRevision(root.revisionId, root.revisionNumber)
        if (localVault.rootRevision != remoteRevision) return false

        val pointer = root.manga.firstOrNull { it.identity == mangaIdentity } ?: return false
        val manifestPath = config.rootPath.childPath(pointer.path)
        val manifestBody = storage.get(manifestPath) ?: return false
        val remoteManga = when (val result = codec.decodeManga(manifestBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            else -> return false
        }
        if (remoteManga.vaultIdentity != root.identity || remoteManga.mangaIdentity != pointer.identity) return false

        val oldThumbnail = remoteManga.chapters
            .firstOrNull { it.identity == chapterIdentity }
            ?.thumbnail
        val oldThumbnailPath = oldThumbnail?.path
        val thumbnailIdentity = identityFactory()
        val thumbnailPath = "content/$mangaIdentity/$chapterIdentity/thumbnail/$thumbnailIdentity.jpg"
        val integrity = bytes.vaultTransferIntegrity()

        storage.createDirectory(config.rootPath.childPath("content"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity/$chapterIdentity"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity/$chapterIdentity/thumbnail"))
        if (!putBytesStaged(storage, config.rootPath.childPath(thumbnailPath), bytes, "image/jpeg")) {
            return false
        }

        val timestamp = now()
        val mangaRevisionId = identityFactory()
        val updatedManga = remoteManga.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = mangaRevisionId,
            revisionNumber = remoteManga.revisionNumber + 1,
            chapters = remoteManga.chapters.map { chapter ->
                if (chapter.identity == chapterIdentity) {
                    chapter.copy(
                        thumbnail = VaultManifestChapterThumbnail(
                            identity = thumbnailIdentity,
                            path = thumbnailPath,
                            mediaType = "image/jpeg",
                            integrity = VaultContentIntegrity(
                                sizeBytes = integrity.sizeBytes,
                                checksumSha256 = integrity.checksumSha256,
                            ),
                            revisionId = identityFactory(),
                            revisionNumber = (chapter.thumbnail?.revisionNumber ?: 0) + 1,
                            updatedAt = timestamp,
                        ),
                        revisionId = identityFactory(),
                        revisionNumber = chapter.revisionNumber + 1,
                        updatedAt = timestamp,
                    )
                } else {
                    chapter
                }
            },
            updatedAt = timestamp,
        )
        val updatedRoot = root.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = identityFactory(),
            revisionNumber = root.revisionNumber + 1,
            updatedAt = timestamp,
            summary = VaultCatalogueSummary(
                mangaCount = root.summary.mangaCount,
                chapterCount = root.summary.chapterCount,
                labelCount = root.summary.labelCount,
                updatedAt = timestamp,
            ),
            manga = root.manga.map {
                if (it.identity == pointer.identity) {
                    it.copy(
                        revisionId = mangaRevisionId,
                        revisionNumber = updatedManga.revisionNumber,
                        updatedAt = timestamp,
                    )
                } else {
                    it
                }
            },
        )

        if (!putTextStaged(storage, manifestPath, codec.encodeManga(updatedManga))) return false
        if (!putTextStaged(storage, rootPath, codec.encodeRoot(updatedRoot))) return false

        val newCacheKey = VaultChapterThumbnailCacheKey(
            vaultId = localVault.id,
            mangaIdentity = mangaIdentity,
            chapterIdentity = chapterIdentity,
            thumbnailIdentity = thumbnailIdentity,
            remotePath = thumbnailPath,
        )
        cacheStore.write(newCacheKey, bytes)

        val refreshed = refreshService.refreshConfiguredVault() is VaultCatalogueRefreshResult.Refreshed
        if (refreshed) {
            oldThumbnail?.let {
                cacheStore.delete(
                    VaultChapterThumbnailCacheKey(
                        vaultId = localVault.id,
                        mangaIdentity = mangaIdentity,
                        chapterIdentity = chapterIdentity,
                        thumbnailIdentity = it.identity,
                        remotePath = it.path,
                    ),
                )
            }
            oldThumbnailPath?.let { storage.delete(config.rootPath.childPath(it)) }
        } else {
            cacheStore.delete(newCacheKey)
        }
        return refreshed
    }

    private suspend fun hasActiveTransfer(mangaId: Long, vaultId: Long, mangaIdentity: String): Boolean {
        val chapterIds = repository.getChapters(mangaId).map { it.id }.toSet()
        return repository.getTransferJobsForVault(vaultId).any { job ->
            job.state in ACTIVE_TRANSFER_STATES &&
                (job.chapterId in chapterIds || job.remotePath?.contains("content/$mangaIdentity/") == true)
        }
    }

    private suspend fun markJobRunning(jobId: Long) {
        val job = repository.getTransferJob(jobId) ?: return
        val timestamp = now()
        repository.upsertTransferJob(
            job.copy(
                state = VaultTransferState.RUNNING,
                attempts = job.attempts + 1,
                updatedAt = timestamp,
                startedAt = timestamp,
                completedAt = null,
            ),
        )
    }

    private suspend fun markJobSucceeded(jobId: Long) {
        val job = repository.getTransferJob(jobId) ?: return
        val timestamp = now()
        repository.upsertTransferJob(
            job.copy(
                state = VaultTransferState.SUCCEEDED,
                updatedAt = timestamp,
                completedAt = timestamp,
                failureReason = null,
            ),
        )
    }

    private suspend fun markJobFailed(jobId: Long, reason: String) {
        val job = repository.getTransferJob(jobId) ?: return
        val timestamp = now()
        repository.upsertTransferJob(
            job.copy(
                state = VaultTransferState.FAILED,
                updatedAt = timestamp,
                completedAt = timestamp,
                failureReason = reason,
            ),
        )
    }

    private suspend fun putTextStaged(storage: VaultWebDav, path: String, body: String): Boolean {
        return putBytesStaged(storage, path, body.toByteArray(), "application/json")
    }

    private suspend fun putBytesStaged(
        storage: VaultWebDav,
        path: String,
        bytes: ByteArray,
        mediaType: String?,
    ): Boolean {
        val stagedPath = "$path.staged-${identityFactory()}"
        return runCatching {
            check(storage.putBytes(stagedPath, bytes, mediaType)) { "remote upload failed" }
            check(storage.promote(stagedPath, path)) { "remote promote failed" }
        }.onFailure {
            storage.delete(stagedPath)
        }.isSuccess
    }

    private companion object {
        val ACTIVE_TRANSFER_STATES = setOf(VaultTransferState.QUEUED, VaultTransferState.RUNNING)
    }
}

data class VaultChapterThumbnailPublishRequest(
    val mangaId: Long,
    val chapterId: Long,
    val chapterIdentity: VaultIdentity,
    val sourcePageNumber: Int,
    val jpegBytes: ByteArray,
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
