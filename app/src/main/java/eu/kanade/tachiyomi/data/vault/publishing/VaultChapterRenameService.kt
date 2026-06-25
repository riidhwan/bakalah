package eu.kanade.tachiyomi.data.vault.publishing

import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.childPath
import eu.kanade.tachiyomi.data.vault.remote.getTextOrNull
import eu.kanade.tachiyomi.data.vault.remote.isSuccess
import eu.kanade.tachiyomi.data.vault.remote.webdav.WebDavVaultRemoteStorageFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
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
import java.util.UUID

class VaultChapterRenameService internal constructor(
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val remoteStorageFactory: VaultRemoteStorageFactory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        networkHelper: NetworkHelper,
        json: Json,
        repository: VaultRepository,
        preferences: ContentVaultPreferences,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        json = json,
        repository = repository,
        preferences = preferences,
        remoteStorageFactory = WebDavVaultRemoteStorageFactory(networkHelper),
        now = now,
    )

    private val codec = VaultManifestCodec(json)

    suspend fun rename(
        mangaId: Long,
        chapterId: Long,
        chapterIdentity: String,
        title: String,
        ignoredJobId: Long? = null,
    ): VaultChapterRenameResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return VaultChapterRenameResult.TitleRequired

        val manga = repository.getMangaById(mangaId) ?: return VaultChapterRenameResult.MangaNotFound
        val localChapter = repository.getChapters(manga.id).firstOrNull { it.id == chapterId }
        if (localChapter != null && localChapter.identity.value != chapterIdentity) {
            return VaultChapterRenameResult.ChapterIdentityMismatch
        }
        if (localChapter == null && chapterIdentity.isBlank()) return VaultChapterRenameResult.ChapterNotFound
        if (hasBlockingActiveTransfer(manga.id, manga.vaultId, chapterId, ignoredJobId)) {
            return VaultChapterRenameResult.BlockedByActiveTransfer
        }

        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultChapterRenameResult.IncompleteConfiguration

        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootBody = get(config, rootPath) ?: return VaultChapterRenameResult.ManifestNotFound(rootPath)
        val root = when (val result = codec.decodeRoot(rootBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultChapterRenameResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultChapterRenameResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultChapterRenameResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultChapterRenameResult.Malformed(rootPath)
        }

        val expectedIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (expectedIdentity != null && expectedIdentity != root.identity) {
            return VaultChapterRenameResult.IdentityChanged(ContentVaultIdentity(root.identity))
        }
        val localVault = repository.getVaultByIdentity(ContentVaultIdentity(root.identity))
            ?: return VaultChapterRenameResult.VaultNotFound
        val remoteRevision = VaultRevision(root.revisionId, root.revisionNumber)
        if (localVault.rootRevision != remoteRevision) {
            return VaultChapterRenameResult.RevisionMismatch(remoteRevision)
        }

        val pointer = root.manga.firstOrNull { it.identity == manga.identity.value }
            ?: return VaultChapterRenameResult.MangaNotFound
        val mangaPath = config.rootPath.childPath(pointer.path)
        val mangaBody = get(config, mangaPath) ?: return VaultChapterRenameResult.ManifestNotFound(pointer.path)
        val remoteManga = when (val result = codec.decodeManga(mangaBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultChapterRenameResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultChapterRenameResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultChapterRenameResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultChapterRenameResult.Malformed(pointer.path)
        }
        if (remoteManga.vaultIdentity != root.identity || remoteManga.mangaIdentity != pointer.identity) {
            return VaultChapterRenameResult.IdentityMismatch(pointer.path)
        }

        val target = remoteManga.chapters.firstOrNull { it.identity == chapterIdentity }
            ?: return VaultChapterRenameResult.ChapterNotFound
        val timestamp = now()
        val updatedChapter = target.copy(
            title = trimmedTitle,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = target.revisionNumber + 1,
            updatedAt = timestamp,
        )
        val updatedManga = remoteManga.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = remoteManga.revisionNumber + 1,
            chapters = remoteManga.chapters.map {
                if (it.identity == target.identity) updatedChapter else it
            },
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
                chapterCount = root.summary.chapterCount,
                labelCount = root.summary.labelCount,
                updatedAt = timestamp,
            ),
            manga = updatedPointers,
        )

        if (!putStaged(config, mangaPath, codec.encodeManga(updatedManga))) {
            return VaultChapterRenameResult.PublishFailed
        }
        if (!putStaged(config, rootPath, codec.encodeRoot(updatedRoot))) {
            putStaged(config, mangaPath, mangaBody)
            return VaultChapterRenameResult.PublishFailed
        }

        return VaultChapterRenameResult.Renamed
    }

    private suspend fun hasBlockingActiveTransfer(
        mangaId: Long,
        vaultId: Long,
        chapterId: Long,
        ignoredJobId: Long?,
    ): Boolean {
        return repository.getTransferJobsForVault(vaultId).any { job ->
            if (job.id == ignoredJobId || job.state !in ACTIVE_TRANSFER_STATES) return@any false
            if (job.chapterId == chapterId && job.type != VaultTransferType.CHAPTER_RENAME) return@any true
            job.mangaId == mangaId && job.type in MANGA_MANIFEST_MUTATION_TYPES
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

    private suspend fun delete(config: WebDavVaultConfig, path: String) {
        remoteStorageFactory.create(config).delete(path)
    }

    private companion object {
        val ACTIVE_TRANSFER_STATES = setOf(VaultTransferState.QUEUED, VaultTransferState.RUNNING)
        val MANGA_MANIFEST_MUTATION_TYPES = setOf(
            VaultTransferType.IMPORT_PUBLISH,
            VaultTransferType.CAPTURE_PUBLISH,
            VaultTransferType.METADATA_PUBLISH,
            VaultTransferType.THUMBNAIL_PUBLISH,
            VaultTransferType.CHAPTER_DELETE,
            VaultTransferType.CHAPTER_RENAME,
        )
    }
}

sealed interface VaultChapterRenameResult {
    data object Renamed : VaultChapterRenameResult
    data object TitleRequired : VaultChapterRenameResult
    data object BlockedByActiveTransfer : VaultChapterRenameResult
    data object IncompleteConfiguration : VaultChapterRenameResult
    data object VaultNotFound : VaultChapterRenameResult
    data object MangaNotFound : VaultChapterRenameResult
    data object ChapterNotFound : VaultChapterRenameResult
    data object ChapterIdentityMismatch : VaultChapterRenameResult
    data object NotVault : VaultChapterRenameResult
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultChapterRenameResult
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultChapterRenameResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : VaultChapterRenameResult
    data class RevisionMismatch(val currentRevision: VaultRevision) : VaultChapterRenameResult
    data class ManifestNotFound(val manifestPath: String) : VaultChapterRenameResult
    data class IdentityMismatch(val manifestPath: String) : VaultChapterRenameResult
    data class Malformed(val manifestPath: String) : VaultChapterRenameResult
    data object PublishFailed : VaultChapterRenameResult
}
