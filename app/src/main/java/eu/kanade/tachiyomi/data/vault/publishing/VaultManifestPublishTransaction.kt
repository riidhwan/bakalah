package eu.kanade.tachiyomi.data.vault.publishing

import eu.kanade.tachiyomi.data.vault.localimport.childPath
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultMangaManifestPointer
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRootManifest
import tachiyomi.domain.vault.model.WebDavVaultConfig
import java.util.UUID

internal interface VaultManifestPublishStorage {
    suspend fun get(path: String): String?
    suspend fun put(path: String, body: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun createDirectory(path: String): Boolean
    suspend fun promote(stagedPath: String, finalPath: String): Boolean
}

internal class VaultManifestPublishTransaction(json: Json) {
    private val codec = VaultManifestCodec(json)

    suspend fun prepare(
        storage: VaultManifestPublishStorage,
        config: WebDavVaultConfig,
        target: VaultManifestPublishTarget,
        expectedVaultIdentity: String?,
        globalFailure: (String) -> Throwable,
    ): VaultManifestPublishContext {
        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootManifest = readRootManifest(storage, rootPath)
            ?: throw globalFailure("manifest")
        if (expectedVaultIdentity != null && rootManifest.identity != expectedVaultIdentity) {
            throw globalFailure("identity")
        }

        val mangaManifestPath = when (target) {
            is VaultManifestPublishTarget.Existing ->
                rootManifest.manga
                    .firstOrNull { it.identity == target.mangaIdentity }
                    ?.path
                    ?: throw globalFailure("target")
            is VaultManifestPublishTarget.CreateNew -> target.manifestPath
            is VaultManifestPublishTarget.Created -> target.manifestPath
        }
        val remoteMangaManifest = when (target) {
            is VaultManifestPublishTarget.Existing ->
                storage.get(config.rootPath.childPath(mangaManifestPath))
                    ?.let { decodeMangaManifest(it) }
                    ?: throw globalFailure("target")
            is VaultManifestPublishTarget.CreateNew ->
                storage.get(config.rootPath.childPath(mangaManifestPath))
                    ?.let { decodeMangaManifest(it) }
            is VaultManifestPublishTarget.Created ->
                storage.get(config.rootPath.childPath(mangaManifestPath))
                    ?.let { decodeMangaManifest(it) }
                    ?: throw globalFailure("target")
        }

        return VaultManifestPublishContext(
            rootPath = rootPath,
            rootManifest = rootManifest,
            mangaManifestPath = mangaManifestPath,
            mangaIdentity = target.mangaIdentity,
            remoteMangaManifest = remoteMangaManifest,
        )
    }

    suspend fun commit(
        storage: VaultManifestPublishStorage,
        config: WebDavVaultConfig,
        context: VaultManifestPublishContext,
        metadata: VaultMetadata,
        mangaManifest: VaultMangaManifest,
        mangaRevisionId: String,
        mangaRevisionNumber: Long,
        now: Long,
        newUploads: List<VaultPromotableUpload>,
        promotedPaths: List<String> = emptyList(),
        globalFailure: (String) -> Throwable,
    ) {
        storage.createDirectory(config.rootPath.childPath("manga"))
        try {
            promoteUploadedContent(storage, config, newUploads, globalFailure)
        } catch (error: Throwable) {
            cleanupUploadedContent(storage, config, newUploads, promotedPaths)
            throw error
        }
        if (!storage.put(config.rootPath.childPath(context.mangaManifestPath), codec.encodeManga(mangaManifest))) {
            cleanupUploadedContent(storage, config, newUploads, promotedPaths)
            error("publish")
        }
        val updatedRoot = context.rootManifest.withUpdatedMangaPointer(
            mangaIdentity = context.mangaIdentity,
            mangaManifestPath = context.mangaManifestPath,
            metadata = metadata,
            mangaRevisionId = mangaRevisionId,
            mangaRevisionNumber = mangaRevisionNumber,
            mangaChapterCount = mangaManifest.chapters.size,
            previousMangaChapterCount = context.remoteMangaManifest?.chapters?.size ?: 0,
            now = now,
        )
        if (!storage.put(context.rootPath, codec.encodeRoot(updatedRoot))) {
            rollbackPublishedMangaManifest(
                storage = storage,
                config = config,
                mangaManifestPath = context.mangaManifestPath,
                previousManifest = context.remoteMangaManifest,
                newUploads = newUploads,
                promotedPaths = promotedPaths,
            )
            throw globalFailure("publish")
        }
    }

    suspend fun cleanupUploadedContent(
        storage: VaultManifestPublishStorage,
        config: WebDavVaultConfig,
        newUploads: List<VaultPromotableUpload>,
        promotedPaths: List<String> = emptyList(),
    ) {
        newUploads.forEach { upload ->
            runCatching { storage.delete(config.rootPath.childPath(upload.stagedPath)) }
            runCatching { storage.delete(config.rootPath.childPath(upload.finalPath)) }
        }
        promotedPaths.forEach { path ->
            runCatching { storage.delete(config.rootPath.childPath(path)) }
        }
    }

    suspend fun promoteOptionalUpload(
        storage: VaultManifestPublishStorage,
        config: WebDavVaultConfig,
        upload: VaultPromotableUpload,
    ): String? {
        val promoted = runCatching {
            storage.promote(
                config.rootPath.childPath(upload.stagedPath),
                config.rootPath.childPath(upload.finalPath),
            )
        }.getOrDefault(false)
        if (promoted) return upload.finalPath

        cleanupUploadedContent(
            storage = storage,
            config = config,
            newUploads = listOf(upload),
        )
        return null
    }

    private suspend fun promoteUploadedContent(
        storage: VaultManifestPublishStorage,
        config: WebDavVaultConfig,
        newUploads: List<VaultPromotableUpload>,
        globalFailure: (String) -> Throwable,
    ) {
        newUploads.forEach { upload ->
            if (!storage.promote(
                    config.rootPath.childPath(upload.stagedPath),
                    config.rootPath.childPath(upload.finalPath),
                )
            ) {
                throw globalFailure("promote")
            }
        }
    }

    private suspend fun rollbackPublishedMangaManifest(
        storage: VaultManifestPublishStorage,
        config: WebDavVaultConfig,
        mangaManifestPath: String,
        previousManifest: VaultMangaManifest?,
        newUploads: List<VaultPromotableUpload>,
        promotedPaths: List<String>,
    ) {
        runCatching {
            if (previousManifest != null) {
                storage.put(config.rootPath.childPath(mangaManifestPath), codec.encodeManga(previousManifest))
            } else {
                storage.delete(config.rootPath.childPath(mangaManifestPath))
            }
        }
        cleanupUploadedContent(storage, config, newUploads, promotedPaths)
    }

    private suspend fun readRootManifest(storage: VaultManifestPublishStorage, path: String) =
        storage.get(path)?.let { body ->
            when (val result = codec.decodeRoot(body)) {
                is VaultManifestReadResult.Success -> result.manifest
                else -> null
            }
        }

    private fun decodeMangaManifest(body: String): VaultMangaManifest? {
        return when (val result = codec.decodeManga(body)) {
            is VaultManifestReadResult.Success -> result.manifest
            else -> null
        }
    }

    private fun VaultRootManifest.withUpdatedMangaPointer(
        mangaIdentity: String,
        mangaManifestPath: String,
        metadata: VaultMetadata,
        mangaRevisionId: String,
        mangaRevisionNumber: Long,
        mangaChapterCount: Int,
        previousMangaChapterCount: Int,
        now: Long,
    ): VaultRootManifest {
        val updatedPointers = manga
            .filterNot { it.identity == mangaIdentity }
            .plus(
                VaultMangaManifestPointer(
                    identity = mangaIdentity,
                    path = mangaManifestPath,
                    title = metadata.title,
                    revisionId = mangaRevisionId,
                    revisionNumber = mangaRevisionNumber,
                    updatedAt = now,
                ),
            )
            .sortedBy { VaultMetadata.normalizeTitle(it.title) }
        return copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = revisionNumber + 1,
            updatedAt = now,
            summary = VaultCatalogueSummary(
                mangaCount = updatedPointers.size.toLong(),
                chapterCount = summary.chapterCount - previousMangaChapterCount + mangaChapterCount,
                labelCount = summary.labelCount,
                updatedAt = now,
            ),
            manga = updatedPointers,
        )
    }
}

internal sealed interface VaultManifestPublishTarget {
    val mangaIdentity: String

    data class Existing(
        override val mangaIdentity: String,
    ) : VaultManifestPublishTarget

    data class CreateNew(
        override val mangaIdentity: String,
        val manifestPath: String,
    ) : VaultManifestPublishTarget

    data class Created(
        override val mangaIdentity: String,
        val manifestPath: String,
    ) : VaultManifestPublishTarget
}

internal data class VaultManifestPublishContext(
    val rootPath: String,
    val rootManifest: VaultRootManifest,
    val mangaManifestPath: String,
    val mangaIdentity: String,
    val remoteMangaManifest: VaultMangaManifest?,
)
