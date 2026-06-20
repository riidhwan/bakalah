package eu.kanade.tachiyomi.data.vault.publishing

import eu.kanade.tachiyomi.data.vault.cache.VaultCachePolicyService
import eu.kanade.tachiyomi.data.vault.reader.ActiveVaultReaderSessions
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshResult
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshService
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteWriteResult
import eu.kanade.tachiyomi.data.vault.remote.childPath
import eu.kanade.tachiyomi.data.vault.remote.getTextOrNull
import eu.kanade.tachiyomi.data.vault.remote.isSuccess
import eu.kanade.tachiyomi.data.vault.remote.webdav.WebDavVaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.transfer.UniFileVaultTransferLocalStaging
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.net.HttpURLConnection
import java.util.UUID

class VaultChapterDeletionService internal constructor(
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val refreshService: VaultCatalogueRefreshService,
    private val activeReaderSessions: ActiveVaultReaderSessions,
    private val storageManager: StorageManager,
    private val remoteStorageFactory: VaultRemoteStorageFactory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        networkHelper: NetworkHelper,
        json: Json,
        repository: VaultRepository,
        preferences: ContentVaultPreferences,
        refreshService: VaultCatalogueRefreshService,
        activeReaderSessions: ActiveVaultReaderSessions,
        storageManager: StorageManager,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        json = json,
        repository = repository,
        preferences = preferences,
        refreshService = refreshService,
        activeReaderSessions = activeReaderSessions,
        storageManager = storageManager,
        remoteStorageFactory = WebDavVaultRemoteStorageFactory(networkHelper),
        now = now,
    )

    private val codec = VaultManifestCodec(json)

    suspend fun delete(
        mangaId: Long,
        chapterId: Long,
        ignoredJobId: Long? = null,
    ): VaultChapterDeletionResult {
        val manga = repository.getMangaById(mangaId) ?: return VaultChapterDeletionResult.MangaNotFound
        val localChapters = repository.getChapters(manga.id)
        val chapter = localChapters.firstOrNull { it.id == chapterId }
            ?: return VaultChapterDeletionResult.ChapterNotFound
        if (activeReaderSessions.isActive(manga.id)) {
            return VaultChapterDeletionResult.BlockedByActiveReader
        }
        if (localChapters.size <= 1) {
            return VaultChapterDeletionResult.LastChapter
        }
        if (hasBlockingActiveTransfer(manga.id, manga.vaultId, chapterId, ignoredJobId)) {
            return VaultChapterDeletionResult.BlockedByActiveTransfer
        }

        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultChapterDeletionResult.IncompleteConfiguration

        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootBody = get(config, rootPath) ?: return VaultChapterDeletionResult.ManifestNotFound(rootPath)
        val root = when (val result = codec.decodeRoot(rootBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultChapterDeletionResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultChapterDeletionResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultChapterDeletionResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultChapterDeletionResult.Malformed(rootPath)
        }

        val expectedIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (expectedIdentity != null && expectedIdentity != root.identity) {
            return VaultChapterDeletionResult.IdentityChanged(ContentVaultIdentity(root.identity))
        }
        val localVault = repository.getVaultByIdentity(ContentVaultIdentity(root.identity))
            ?: return VaultChapterDeletionResult.VaultNotFound
        val remoteRevision = VaultRevision(root.revisionId, root.revisionNumber)
        if (localVault.rootRevision != remoteRevision) {
            return VaultChapterDeletionResult.RevisionMismatch(remoteRevision)
        }

        val pointer = root.manga.firstOrNull { it.identity == manga.identity.value }
            ?: return VaultChapterDeletionResult.MangaNotFound
        val mangaPath = config.rootPath.childPath(pointer.path)
        val mangaBody = get(config, mangaPath) ?: return VaultChapterDeletionResult.ManifestNotFound(pointer.path)
        val remoteManga = when (val result = codec.decodeManga(mangaBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultChapterDeletionResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultChapterDeletionResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultChapterDeletionResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultChapterDeletionResult.Malformed(pointer.path)
        }
        if (remoteManga.vaultIdentity != root.identity || remoteManga.mangaIdentity != pointer.identity) {
            return VaultChapterDeletionResult.IdentityMismatch(pointer.path)
        }

        val target = remoteManga.chapters.firstOrNull { it.identity == chapter.identity.value }
        if (target == null) {
            if (remoteManga.chapters.isEmpty()) return VaultChapterDeletionResult.LastChapter
            return completeAlreadyDeleted(manga.id, chapter.id)
        }
        val updatedChapters = remoteManga.chapters.filterNot { it.identity == target.identity }
        if (updatedChapters.isEmpty()) return VaultChapterDeletionResult.LastChapter

        val timestamp = now()
        val updatedManga = remoteManga.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = remoteManga.revisionNumber + 1,
            chapters = updatedChapters,
            chapterProvenance = remoteManga.chapterProvenance.filterNot { it.chapterIdentity == target.identity },
            updatedAt = timestamp,
        )
        val updatedPointers = root.manga.map {
            if (it.identity == pointer.identity) {
                it.copy(
                    revisionId = updatedManga.revisionId,
                    revisionNumber = updatedManga.revisionNumber,
                    updatedAt = timestamp,
                )
            } else {
                it
            }
        }
        val updatedRoot = root.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = root.revisionNumber + 1,
            updatedAt = timestamp,
            summary = VaultCatalogueSummary(
                mangaCount = updatedPointers.size.toLong(),
                chapterCount = (root.summary.chapterCount - 1).coerceAtLeast(0),
                labelCount = root.summary.labelCount,
                updatedAt = timestamp,
            ),
            manga = updatedPointers,
        )

        if (!putStaged(config, mangaPath, codec.encodeManga(updatedManga))) {
            return VaultChapterDeletionResult.PublishFailed
        }
        if (!putStaged(config, rootPath, codec.encodeRoot(updatedRoot))) {
            putStaged(config, mangaPath, mangaBody)
            return VaultChapterDeletionResult.PublishFailed
        }

        evictLocalCache(chapter.id)
        val refreshResult = refreshLocalIndex()
        refreshResult?.let { return it }

        val cleanupFailures = cleanupRemoteFiles(
            config = config,
            paths = listOfNotNull(target.content.path, target.thumbnail?.path),
        )
        return if (cleanupFailures.isEmpty()) {
            VaultChapterDeletionResult.Deleted
        } else {
            VaultChapterDeletionResult.DeletedWithCleanupFailures(cleanupFailures)
        }
    }

    private suspend fun completeAlreadyDeleted(mangaId: Long, chapterId: Long): VaultChapterDeletionResult {
        evictLocalCache(chapterId)
        val refreshResult = refreshLocalIndex()
        refreshResult?.let { return it }
        return if (repository.getChapters(mangaId).none { it.id == chapterId }) {
            VaultChapterDeletionResult.Deleted
        } else {
            VaultChapterDeletionResult.ChapterNotFound
        }
    }

    private suspend fun hasBlockingActiveTransfer(
        mangaId: Long,
        vaultId: Long,
        chapterId: Long,
        ignoredJobId: Long?,
    ): Boolean {
        return repository.getTransferJobsForVault(vaultId).any { job ->
            if (job.id == ignoredJobId || job.state !in ACTIVE_TRANSFER_STATES) return@any false
            if (job.chapterId == chapterId && job.type != VaultTransferType.CHAPTER_DELETE) return@any true
            if (job.type == VaultTransferType.CHAPTER_DELETE) {
                return@any job.state == VaultTransferState.RUNNING && job.mangaId == mangaId
            }
            job.mangaId == mangaId && job.type in MANGA_MANIFEST_MUTATION_TYPES
        }
    }

    private suspend fun refreshLocalIndex(): VaultChapterDeletionResult? {
        return when (val refresh = refreshService.refreshConfiguredVault()) {
            is VaultCatalogueRefreshResult.Refreshed -> null
            VaultCatalogueRefreshResult.IncompleteConfiguration -> VaultChapterDeletionResult.IncompleteConfiguration
            VaultCatalogueRefreshResult.NotVault -> VaultChapterDeletionResult.NotVault
            is VaultCatalogueRefreshResult.UnsupportedOlderVersion ->
                VaultChapterDeletionResult.UnsupportedOlderVersion(refresh.layoutVersion)
            is VaultCatalogueRefreshResult.UnsupportedNewerVersion ->
                VaultChapterDeletionResult.UnsupportedNewerVersion(refresh.layoutVersion)
            is VaultCatalogueRefreshResult.IdentityChanged ->
                VaultChapterDeletionResult.IdentityChanged(refresh.remoteIdentity)
            is VaultCatalogueRefreshResult.ManifestNotFound ->
                VaultChapterDeletionResult.ManifestNotFound(refresh.manifestPath)
            is VaultCatalogueRefreshResult.IdentityMismatch ->
                VaultChapterDeletionResult.IdentityMismatch(refresh.manifestPath)
            is VaultCatalogueRefreshResult.Malformed -> VaultChapterDeletionResult.Malformed(refresh.manifestPath)
        }
    }

    private suspend fun get(config: WebDavVaultConfig, path: String): String? =
        remoteStorageFactory.create(config).getTextOrNull(path)

    private suspend fun putStaged(config: WebDavVaultConfig, path: String, body: String): Boolean {
        val stagedPath = "$path.staged-${UUID.randomUUID()}"
        return runCatching {
            put(config, stagedPath, body)
            move(config, stagedPath, path)
        }.onFailure {
            delete(config, stagedPath)
        }.isSuccess
    }

    private suspend fun put(config: WebDavVaultConfig, path: String, body: String) {
        check(remoteStorageFactory.create(config).putText(path, body).isSuccess()) { "remote upload failed" }
    }

    private suspend fun move(config: WebDavVaultConfig, stagedPath: String, finalPath: String) {
        check(remoteStorageFactory.create(config).move(stagedPath, finalPath).isSuccess()) { "remote promote failed" }
    }

    private suspend fun cleanupRemoteFiles(config: WebDavVaultConfig, paths: List<String>): List<String> {
        return paths.distinct().filterNot { delete(config, config.rootPath.childPath(it)) }
    }

    private suspend fun evictLocalCache(chapterId: Long) {
        val root = storageManager.getVaultCacheDirectory() ?: return
        VaultCachePolicyService(
            repository = repository,
            localStaging = UniFileVaultTransferLocalStaging(root),
            preferences = preferences,
        ).evictChapter(chapterId)
    }

    private suspend fun delete(config: WebDavVaultConfig, path: String): Boolean {
        val result = remoteStorageFactory.create(config).delete(path)
        return result.isSuccess() ||
            (result as? VaultRemoteWriteResult.Failed)?.statusCode == HttpURLConnection.HTTP_GONE
    }

    private companion object {
        val ACTIVE_TRANSFER_STATES = setOf(VaultTransferState.QUEUED, VaultTransferState.RUNNING)
        val MANGA_MANIFEST_MUTATION_TYPES = setOf(
            VaultTransferType.IMPORT_PUBLISH,
            VaultTransferType.CAPTURE_PUBLISH,
            VaultTransferType.METADATA_PUBLISH,
            VaultTransferType.THUMBNAIL_PUBLISH,
            VaultTransferType.CHAPTER_DELETE,
        )
    }
}

sealed interface VaultChapterDeletionResult {
    data object Deleted : VaultChapterDeletionResult
    data class DeletedWithCleanupFailures(val failedPaths: List<String>) : VaultChapterDeletionResult
    data object BlockedByActiveTransfer : VaultChapterDeletionResult
    data object BlockedByActiveReader : VaultChapterDeletionResult
    data object LastChapter : VaultChapterDeletionResult
    data object IncompleteConfiguration : VaultChapterDeletionResult
    data object VaultNotFound : VaultChapterDeletionResult
    data object MangaNotFound : VaultChapterDeletionResult
    data object ChapterNotFound : VaultChapterDeletionResult
    data object NotVault : VaultChapterDeletionResult
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultChapterDeletionResult
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultChapterDeletionResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : VaultChapterDeletionResult
    data class RevisionMismatch(val currentRevision: VaultRevision) : VaultChapterDeletionResult
    data class ManifestNotFound(val manifestPath: String) : VaultChapterDeletionResult
    data class IdentityMismatch(val manifestPath: String) : VaultChapterDeletionResult
    data class Malformed(val manifestPath: String) : VaultChapterDeletionResult
    data object PublishFailed : VaultChapterDeletionResult
}
