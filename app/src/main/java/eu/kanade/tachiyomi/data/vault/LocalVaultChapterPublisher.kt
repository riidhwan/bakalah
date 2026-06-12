package eu.kanade.tachiyomi.data.vault

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.importing.LocalVaultChapterStager
import eu.kanade.tachiyomi.data.vault.importing.ScannedLocalVaultChapter
import eu.kanade.tachiyomi.data.vault.importing.childPath
import eu.kanade.tachiyomi.data.vault.importing.coverMediaType
import eu.kanade.tachiyomi.data.vault.importing.digest
import eu.kanade.tachiyomi.data.vault.importing.duplicateFileKey
import eu.kanade.tachiyomi.data.vault.importing.listFilesRecursively
import eu.kanade.tachiyomi.data.vault.importing.orderVaultImportChapters
import eu.kanade.tachiyomi.data.vault.importing.relativePathFrom
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.LocalVaultImportManga
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultMangaManifestPointer
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestCover
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultManifestProvenance
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.File
import java.util.UUID

internal interface LocalVaultChapterPublisherBoundary {
    suspend fun publish(
        webDav: VaultWebDav,
        config: WebDavVaultConfig,
        vaultIdentity: ContentVaultIdentity,
        expectedVaultIdentity: String?,
        importManga: LocalVaultImportManga,
        localChapter: ScannedLocalVaultChapter,
        coverFile: UniFile?,
        target: LocalVaultActiveTarget,
        allowReplacement: Boolean,
        stagingRoot: File,
        localSourceName: String?,
        progressPhase: (VaultImportProgressPhase) -> Unit,
    ): LocalVaultChapterPublishResult
}

internal class LocalVaultChapterPublisher(
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val chapterStager: LocalVaultChapterStager,
) : LocalVaultChapterPublisherBoundary {
    private val codec = VaultManifestCodec(json)

    override suspend fun publish(
        webDav: VaultWebDav,
        config: WebDavVaultConfig,
        vaultIdentity: ContentVaultIdentity,
        expectedVaultIdentity: String?,
        importManga: LocalVaultImportManga,
        localChapter: ScannedLocalVaultChapter,
        coverFile: UniFile?,
        target: LocalVaultActiveTarget,
        allowReplacement: Boolean,
        stagingRoot: File,
        localSourceName: String?,
        progressPhase: (VaultImportProgressPhase) -> Unit,
    ): LocalVaultChapterPublishResult {
        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootManifest = readRootManifest(webDav, rootPath)
            ?: throw LocalImportGlobalFailure("manifest", localChapter.chapter.title)
        if (expectedVaultIdentity != null && rootManifest.identity != expectedVaultIdentity) {
            throw LocalImportGlobalFailure("identity", localChapter.chapter.title)
        }

        val mangaManifestPath = when (target) {
            is LocalVaultActiveTarget.Existing ->
                rootManifest.manga
                    .firstOrNull { it.identity == target.manga.identity.value }
                    ?.path
                    ?: throw LocalImportGlobalFailure("target", localChapter.chapter.title)
            is LocalVaultActiveTarget.CreateNew -> target.manifestPath
            is LocalVaultActiveTarget.Created -> target.manifestPath
        }
        val remoteMangaManifest = when (target) {
            is LocalVaultActiveTarget.Existing -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
                ?: throw LocalImportGlobalFailure("target", localChapter.chapter.title)
            is LocalVaultActiveTarget.CreateNew -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
            is LocalVaultActiveTarget.Created -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
                ?: throw LocalImportGlobalFailure("target", localChapter.chapter.title)
        }
        val mangaIdentity = when (target) {
            is LocalVaultActiveTarget.Existing -> target.manga.identity.value
            is LocalVaultActiveTarget.CreateNew -> target.mangaIdentity
            is LocalVaultActiveTarget.Created -> target.mangaIdentity
        }
        val now = System.currentTimeMillis()
        val existingRemoteChapters = remoteMangaManifest?.chapters.orEmpty()
        val existingRemoteChaptersByFileKey = existingRemoteChapters
            .associateBy { it.content.path.substringAfterLast('/').duplicateFileKey() }
        val replacement = existingRemoteChaptersByFileKey[
            localChapter.chapter.sourceFileName.duplicateFileKey(),
        ]
        if (replacement != null && !allowReplacement) {
            error("unconfirmed_duplicate")
        }

        progressPhase(VaultImportProgressPhase.COMPRESSING)
        val preparedChapter = chapterStager.stageForUpload(localChapter, stagingRoot)
        val chapterIdentity = replacement?.identity ?: UUID.randomUUID().toString()
        val contentIdentity = if (replacement == null) chapterIdentity else UUID.randomUUID().toString()
        var contentPath: String? = null
        var newCoverPath: String? = null
        try {
            progressPhase(VaultImportProgressPhase.UPLOADING)
            contentPath = uploadChapter(
                webDav = webDav,
                config = config,
                mangaIdentity = mangaIdentity,
                contentIdentity = contentIdentity,
                localChapter = preparedChapter,
            )
            val manifestChapter = if (replacement != null) {
                preparedChapter.toReplacementManifestChapter(
                    existing = replacement,
                    contentPath = contentPath,
                    now = now,
                )
            } else {
                preparedChapter.toManifestChapter(
                    identity = chapterIdentity,
                    contentPath = contentPath,
                    now = now,
                )
            }
            val replacedChapterIdentities = setOfNotNull(replacement?.identity)
            val metadata = when (target) {
                is LocalVaultActiveTarget.Existing -> target.manga.metadata
                is LocalVaultActiveTarget.CreateNew,
                is LocalVaultActiveTarget.Created,
                -> importManga.metadata
            }
            val importedCover = remoteMangaManifest?.cover ?: runCatching {
                progressPhase(VaultImportProgressPhase.UPLOADING)
                uploadCover(
                    webDav = webDav,
                    config = config,
                    mangaIdentity = mangaIdentity,
                    coverFile = coverFile,
                    now = now,
                )?.also { newCoverPath = it.path }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
            val mangaRevision = remoteMangaManifest?.revisionNumber?.plus(1) ?: 1
            val mangaRevisionId = UUID.randomUUID().toString()
            val mangaManifest = VaultMangaManifest(
                layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
                vaultIdentity = rootManifest.identity,
                mangaIdentity = mangaIdentity,
                revisionId = mangaRevisionId,
                revisionNumber = mangaRevision,
                metadata = metadata.toManifestMetadata(),
                labels = remoteMangaManifest?.labels.orEmpty(),
                cover = importedCover,
                chapters = orderVaultImportChapters(
                    chapters = existingRemoteChapters.filterNot {
                        it.identity in replacedChapterIdentities
                    } + manifestChapter,
                    replacementIdentities = replacedChapterIdentities,
                ),
                provenance = remoteMangaManifest?.provenance ?: VaultManifestProvenance(
                    importedFrom = "local",
                    sourceName = localSourceName,
                    sourceUri = importManga.localMangaIdentity,
                    importedAt = now,
                ),
                createdAt = remoteMangaManifest?.createdAt ?: now,
                updatedAt = now,
            )

            webDav.createDirectory(config.rootPath.childPath("manga"))
            progressPhase(VaultImportProgressPhase.PUBLISHING)
            if (!webDav.put(config.rootPath.childPath(mangaManifestPath), codec.encodeManga(mangaManifest))) {
                error("publish")
            }

            val updatedPointers = rootManifest.manga
                .filterNot { it.identity == mangaIdentity }
                .plus(
                    VaultMangaManifestPointer(
                        identity = mangaIdentity,
                        path = mangaManifestPath,
                        title = metadata.title,
                        revisionId = mangaRevisionId,
                        revisionNumber = mangaRevision,
                        updatedAt = now,
                    ),
                )
                .sortedBy { VaultMetadata.normalizeTitle(it.title) }
            val updatedRoot = rootManifest.copy(
                layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
                revisionId = UUID.randomUUID().toString(),
                revisionNumber = rootManifest.revisionNumber + 1,
                updatedAt = now,
                summary = VaultCatalogueSummary(
                    mangaCount = updatedPointers.size.toLong(),
                    chapterCount = rootManifest.summary.chapterCount -
                        (remoteMangaManifest?.chapters?.size ?: 0) +
                        mangaManifest.chapters.size,
                    labelCount = rootManifest.summary.labelCount,
                    updatedAt = now,
                ),
                manga = updatedPointers,
            )
            if (!webDav.put(rootPath, codec.encodeRoot(updatedRoot))) {
                rollbackPublishedMangaManifest(
                    webDav = webDav,
                    config = config,
                    mangaManifestPath = mangaManifestPath,
                    previousManifest = remoteMangaManifest,
                    newContentPath = contentPath,
                    newCoverPath = newCoverPath,
                )
                throw LocalImportGlobalFailure("publish", localChapter.chapter.title)
            }

            replacement?.content?.path?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            if (replacement != null) {
                invalidateReplacementCacheState(mangaIdentity, replacement.identity)
            }
            val nextTarget = when (target) {
                is LocalVaultActiveTarget.Existing -> target
                is LocalVaultActiveTarget.CreateNew,
                is LocalVaultActiveTarget.Created,
                -> LocalVaultActiveTarget.Created(
                    mangaIdentity = mangaIdentity,
                    manifestPath = mangaManifestPath,
                )
            }
            return LocalVaultChapterPublishResult(
                target = nextTarget,
                mangaIdentity = VaultIdentity(mangaIdentity),
                replaced = replacement != null,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is LocalImportGlobalFailure) throw error
            contentPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            newCoverPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            throw error
        }
    }

    private suspend fun uploadChapter(
        webDav: VaultWebDav,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        contentIdentity: String,
        localChapter: ScannedLocalVaultChapter,
    ): String {
        val basePath = "content/$mangaIdentity/$contentIdentity"
        val remoteBasePath = config.rootPath.childPath(basePath)
        webDav.createDirectory(config.rootPath.childPath("content"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        return if (localChapter.file.isDirectory) {
            webDav.createDirectory(remoteBasePath)
            localChapter.file.listFilesRecursively().forEach { file ->
                val relativePath = file.relativePathFrom(localChapter.file)
                val path = config.rootPath.childPath("$basePath/$relativePath")
                webDav.createParentDirectories(path)
                if (!webDav.putFile(path, file)) {
                    error("Failed to upload $path")
                }
            }
            basePath
        } else {
            webDav.createDirectory(remoteBasePath)
            val extension = localChapter.file.extension?.let { ".$it" }.orEmpty()
            val path = "$basePath/${localChapter.file.nameWithoutExtension}$extension"
            if (!webDav.putFile(config.rootPath.childPath(path), localChapter.file)) {
                error("Failed to upload $path")
            }
            path
        }
    }

    private suspend fun uploadCover(
        webDav: VaultWebDav,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        coverFile: UniFile?,
        now: Long,
    ): VaultManifestCover? {
        coverFile ?: return null
        val coverIdentity = UUID.randomUUID().toString()
        val extension = coverFile.extension
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?: "img"
        val path = "content/$mangaIdentity/cover/$coverIdentity.$extension"
        val digest = coverFile.digest()

        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity/cover"))
        if (!webDav.putFile(config.rootPath.childPath(path), coverFile)) {
            error("Failed to upload $path")
        }

        return VaultManifestCover(
            identity = coverIdentity,
            path = path,
            mediaType = coverFile.coverMediaType(),
            integrity = VaultContentIntegrity(
                sizeBytes = digest.sizeBytes,
                checksumSha256 = digest.sha256,
            ),
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = 1,
            updatedAt = now,
        )
    }

    private suspend fun readRootManifest(webDav: VaultWebDav, path: String) =
        webDav.get(path)?.let { body ->
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

    private suspend fun invalidateReplacementCacheState(
        mangaIdentity: String,
        replacedChapterIdentity: String,
    ) {
        val vaultId = preferences.configuredVaultIdentity.get()
            .takeIf { it.isNotBlank() }
            ?.let { repository.getVaultByIdentity(ContentVaultIdentity(it)) }
            ?.id
            ?: return
        val mangaId = repository.getManga(vaultId)
            .firstOrNull { it.identity.value == mangaIdentity }
            ?.id
            ?: return
        val replacedChapterIds = repository.getChapters(mangaId)
            .filter { it.identity.value == replacedChapterIdentity }
            .map { it.id }
        repository.deleteCacheStates(replacedChapterIds)
    }

    private suspend fun rollbackPublishedMangaManifest(
        webDav: VaultWebDav,
        config: WebDavVaultConfig,
        mangaManifestPath: String,
        previousManifest: VaultMangaManifest?,
        newContentPath: String,
        newCoverPath: String?,
    ) {
        runCatching {
            if (previousManifest != null) {
                webDav.put(config.rootPath.childPath(mangaManifestPath), codec.encodeManga(previousManifest))
            } else {
                webDav.delete(config.rootPath.childPath(mangaManifestPath))
            }
        }
        runCatching { webDav.delete(config.rootPath.childPath(newContentPath)) }
        newCoverPath?.let { path ->
            runCatching { webDav.delete(config.rootPath.childPath(path)) }
        }
    }

    private fun ScannedLocalVaultChapter.toManifestChapter(
        identity: String,
        contentPath: String,
        now: Long,
    ) = VaultManifestChapter(
        identity = identity,
        title = chapter.title,
        chapterNumber = chapter.chapterNumber,
        volumeNumber = chapter.volumeNumber,
        scanlator = chapter.scanlator,
        sourceOrder = chapter.sourceOrder,
        content = VaultManifestChapterContent(
            path = contentPath,
            format = chapter.contentFormat,
            integrity = VaultContentIntegrity(
                sizeBytes = chapter.sizeBytes,
                checksumSha256 = chapter.checksumSha256,
            ),
        ),
        revisionId = UUID.randomUUID().toString(),
        revisionNumber = 1,
        dateUpload = chapter.dateUpload,
        createdAt = now,
        updatedAt = now,
    )

    private fun ScannedLocalVaultChapter.toReplacementManifestChapter(
        existing: VaultManifestChapter,
        contentPath: String,
        now: Long,
    ) = existing.copy(
        content = VaultManifestChapterContent(
            path = contentPath,
            format = chapter.contentFormat,
            integrity = VaultContentIntegrity(
                sizeBytes = chapter.sizeBytes,
                checksumSha256 = chapter.checksumSha256,
            ),
        ),
        revisionId = UUID.randomUUID().toString(),
        revisionNumber = existing.revisionNumber + 1,
        updatedAt = now,
    )

    private fun VaultMetadata.toManifestMetadata() = VaultManifestMetadata(
        title = title,
        author = author,
        artist = artist,
        description = description,
        status = status,
    )
}

internal sealed interface LocalVaultActiveTarget {
    val mangaIdentity: String?

    data class Existing(
        val manga: VaultManga,
        val reason: LocalVaultImportTarget.Reason,
    ) : LocalVaultActiveTarget {
        override val mangaIdentity: String = manga.identity.value
    }

    data class CreateNew(
        override val mangaIdentity: String = UUID.randomUUID().toString(),
        val manifestPath: String = "manga/${UUID.randomUUID()}.json",
    ) : LocalVaultActiveTarget

    data class Created(
        override val mangaIdentity: String,
        val manifestPath: String,
    ) : LocalVaultActiveTarget
}

internal data class LocalVaultChapterPublishResult(
    val target: LocalVaultActiveTarget,
    val mangaIdentity: VaultIdentity,
    val replaced: Boolean,
)

internal class LocalImportGlobalFailure(
    val category: String,
    val chapterTitle: String?,
) : RuntimeException(category)
