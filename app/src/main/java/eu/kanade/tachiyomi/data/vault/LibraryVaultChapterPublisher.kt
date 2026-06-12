package eu.kanade.tachiyomi.data.vault

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.importing.childPath
import eu.kanade.tachiyomi.data.vault.importing.toHex
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.vault.interactor.duplicateTitleKey
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.LibraryVaultCaptureManga
import tachiyomi.domain.vault.model.LibraryVaultCaptureTarget
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultMangaManifestPointer
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestChapterProvenance
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
import java.security.MessageDigest
import java.util.UUID

internal interface LibraryVaultChapterPublisherBoundary {
    suspend fun publish(
        webDav: LibraryVaultCaptureWebDav,
        config: WebDavVaultConfig,
        vaultIdentity: ContentVaultIdentity,
        expectedVaultIdentity: String?,
        source: HttpSource,
        manga: Manga,
        captureManga: LibraryVaultCaptureManga,
        chapter: Chapter,
        target: LibraryVaultActiveTarget,
        stagingRoot: File,
        allowReplacement: Boolean,
        progressPhase: (VaultImportProgressPhase) -> Unit,
    ): LibraryVaultChapterPublishResult
}

internal class LibraryVaultChapterPublisher(
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val stager: LibraryVaultChapterStager,
) : LibraryVaultChapterPublisherBoundary {
    private val codec = VaultManifestCodec(json)

    override suspend fun publish(
        webDav: LibraryVaultCaptureWebDav,
        config: WebDavVaultConfig,
        vaultIdentity: ContentVaultIdentity,
        expectedVaultIdentity: String?,
        source: HttpSource,
        manga: Manga,
        captureManga: LibraryVaultCaptureManga,
        chapter: Chapter,
        target: LibraryVaultActiveTarget,
        stagingRoot: File,
        allowReplacement: Boolean,
        progressPhase: (VaultImportProgressPhase) -> Unit,
    ): LibraryVaultChapterPublishResult {
        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootManifest = readRootManifest(webDav, rootPath)
            ?: throw LibraryCaptureGlobalFailure("manifest", chapter.name)
        if (expectedVaultIdentity != null && rootManifest.identity != expectedVaultIdentity) {
            throw LibraryCaptureGlobalFailure("identity", chapter.name)
        }

        val mangaManifestPath = when (target) {
            is LibraryVaultActiveTarget.Existing ->
                rootManifest.manga
                    .firstOrNull { it.identity == target.manga.identity.value }
                    ?.path
                    ?: throw LibraryCaptureGlobalFailure("target", chapter.name)
            is LibraryVaultActiveTarget.CreateNew -> target.manifestPath
            is LibraryVaultActiveTarget.Created -> target.manifestPath
        }
        val remoteMangaManifest = when (target) {
            is LibraryVaultActiveTarget.Existing -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
                ?: throw LibraryCaptureGlobalFailure("target", chapter.name)
            is LibraryVaultActiveTarget.CreateNew -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
            is LibraryVaultActiveTarget.Created -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
                ?: throw LibraryCaptureGlobalFailure("target", chapter.name)
        }
        val mangaIdentity = when (target) {
            is LibraryVaultActiveTarget.Existing -> target.manga.identity.value
            is LibraryVaultActiveTarget.CreateNew -> target.mangaIdentity
            is LibraryVaultActiveTarget.Created -> target.mangaIdentity
        }
        val now = System.currentTimeMillis()
        val stagedChapter = stager.stageForCapture(source, manga, chapter, stagingRoot, progressPhase)
        val existingRemoteChapters = remoteMangaManifest?.chapters.orEmpty()
        val chapterDuplicateTitleKey = chapter.name.duplicateTitleKey()
        val replacement = existingRemoteChapters
            .firstOrNull { it.title.duplicateTitleKey() == chapterDuplicateTitleKey }
        if (replacement != null && !allowReplacement) {
            error("unconfirmed_duplicate")
        }
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
                chapterFile = stagedChapter.file,
            )
            val manifestChapter = if (replacement != null) {
                replacement.copy(
                    content = VaultManifestChapterContent(
                        path = contentPath,
                        format = VaultChapterContentFormat.CBZ,
                        integrity = VaultContentIntegrity(stagedChapter.sizeBytes, stagedChapter.checksumSha256),
                    ),
                    revisionId = UUID.randomUUID().toString(),
                    revisionNumber = replacement.revisionNumber + 1,
                    updatedAt = now,
                )
            } else {
                VaultManifestChapter(
                    identity = chapterIdentity,
                    title = chapter.name,
                    chapterNumber = chapter.chapterNumber,
                    volumeNumber = null,
                    scanlator = chapter.scanlator,
                    sourceOrder = 0,
                    content = VaultManifestChapterContent(
                        path = contentPath,
                        format = VaultChapterContentFormat.CBZ,
                        integrity = VaultContentIntegrity(stagedChapter.sizeBytes, stagedChapter.checksumSha256),
                    ),
                    revisionId = UUID.randomUUID().toString(),
                    revisionNumber = 1,
                    dateUpload = chapter.dateUpload,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            val replacedIdentities = setOfNotNull(replacement?.identity)
            val updatedChapters = orderLibraryVaultCaptureChapters(
                chapters = existingRemoteChapters.filterNot { it.identity in replacedIdentities } + manifestChapter,
                replacementIdentities = replacedIdentities,
            )
            val metadata = when (target) {
                is LibraryVaultActiveTarget.Existing -> target.manga.metadata
                is LibraryVaultActiveTarget.CreateNew,
                is LibraryVaultActiveTarget.Created,
                -> captureManga.metadata
            }
            val importedCover = remoteMangaManifest?.cover ?: runCatching {
                progressPhase(VaultImportProgressPhase.UPLOADING)
                uploadCover(
                    webDav = webDav,
                    config = config,
                    mangaIdentity = mangaIdentity,
                    cover = stager.findCaptureCover(manga, source),
                    now = now,
                )?.also { newCoverPath = it.path }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
            val mangaRevision = remoteMangaManifest?.revisionNumber?.plus(1) ?: 1
            val mangaRevisionId = UUID.randomUUID().toString()
            val provenance = VaultManifestChapterProvenance(
                chapterIdentity = chapterIdentity,
                sourceId = source.id,
                sourceName = source.toString(),
                sourceMangaUrl = manga.url,
                sourceChapterUrl = chapter.url,
                capturedAt = now,
            )
            val mangaManifest = VaultMangaManifest(
                layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
                vaultIdentity = rootManifest.identity,
                mangaIdentity = mangaIdentity,
                revisionId = mangaRevisionId,
                revisionNumber = mangaRevision,
                metadata = metadata.toManifestMetadata(),
                labels = remoteMangaManifest?.labels.orEmpty(),
                cover = importedCover,
                chapters = updatedChapters,
                provenance = remoteMangaManifest?.provenance ?: VaultManifestProvenance(
                    importedFrom = "library-capture",
                    sourceName = source.toString(),
                    sourceUri = manga.url,
                    importedAt = now,
                ),
                chapterProvenance = remoteMangaManifest?.chapterProvenance
                    .orEmpty()
                    .filterNot { it.chapterIdentity == chapterIdentity }
                    .plus(provenance),
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
                throw LibraryCaptureGlobalFailure("publish", chapter.name)
            }

            replacement?.content?.path?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            if (replacement != null) {
                invalidateReplacementCacheState(vaultIdentity, mangaIdentity, replacement.identity)
            }
            val nextTarget = when (target) {
                is LibraryVaultActiveTarget.Existing -> target
                is LibraryVaultActiveTarget.CreateNew,
                is LibraryVaultActiveTarget.Created,
                -> LibraryVaultActiveTarget.Created(
                    mangaIdentity = mangaIdentity,
                    manifestPath = mangaManifestPath,
                )
            }
            return LibraryVaultChapterPublishResult(
                target = nextTarget,
                mangaIdentity = VaultIdentity(mangaIdentity),
                replaced = replacement != null,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is LibraryCaptureGlobalFailure) throw error
            contentPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            newCoverPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            throw error
        }
    }

    private suspend fun uploadChapter(
        webDav: LibraryVaultCaptureWebDav,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        contentIdentity: String,
        chapterFile: UniFile,
    ): String {
        val basePath = "content/$mangaIdentity/$contentIdentity"
        val remoteBasePath = config.rootPath.childPath(basePath)
        webDav.createDirectory(config.rootPath.childPath("content"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        webDav.createDirectory(remoteBasePath)
        val path = "$basePath/$contentIdentity.cbz"
        if (!webDav.putFile(config.rootPath.childPath(path), chapterFile)) {
            error("upload")
        }
        return path
    }

    private suspend fun uploadCover(
        webDav: LibraryVaultCaptureWebDav,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        cover: LibraryVaultCaptureCover?,
        now: Long,
    ): VaultManifestCover? {
        cover ?: return null
        val coverIdentity = UUID.randomUUID().toString()
        val extension = cover.extension
        val path = "content/$mangaIdentity/cover/$coverIdentity.$extension"
        val digest = cover.bytes.digest()

        webDav.createDirectory(config.rootPath.childPath("content"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity/cover"))
        if (!webDav.putBytes(config.rootPath.childPath(path), cover.bytes, cover.mediaType)) {
            error("cover_upload")
        }

        return VaultManifestCover(
            identity = coverIdentity,
            path = path,
            mediaType = cover.mediaType,
            integrity = VaultContentIntegrity(
                sizeBytes = digest.sizeBytes,
                checksumSha256 = digest.sha256,
            ),
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = 1,
            updatedAt = now,
        )
    }

    private suspend fun readRootManifest(webDav: LibraryVaultCaptureWebDav, path: String) =
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

    private suspend fun rollbackPublishedMangaManifest(
        webDav: LibraryVaultCaptureWebDav,
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

    private suspend fun invalidateReplacementCacheState(
        vaultIdentity: ContentVaultIdentity,
        mangaIdentity: String,
        replacedChapterIdentity: String,
    ) {
        val vaultId = repository.getVaultByIdentity(vaultIdentity)?.id ?: return
        val mangaId = repository.getManga(vaultId)
            .firstOrNull { it.identity.value == mangaIdentity }
            ?.id
            ?: return
        val replacedChapterIds = repository.getChapters(mangaId)
            .filter { it.identity.value == replacedChapterIdentity }
            .map { it.id }
        repository.deleteCacheStates(replacedChapterIds)
    }

    private fun VaultMetadata.toManifestMetadata() = VaultManifestMetadata(
        title = title,
        author = author,
        artist = artist,
        description = description,
        status = status,
    )

    private fun ByteArray.digest(): FileDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(this)
        return FileDigest(size.toLong(), digest.digest().toHex())
    }

    private data class FileDigest(
        val sizeBytes: Long,
        val sha256: String,
    )
}

internal sealed interface LibraryVaultActiveTarget {
    val mangaIdentity: String?

    data class Existing(
        val manga: VaultManga,
        val reason: LibraryVaultCaptureTarget.Reason,
    ) : LibraryVaultActiveTarget {
        override val mangaIdentity: String = manga.identity.value
    }

    data class CreateNew(
        override val mangaIdentity: String = UUID.randomUUID().toString(),
        val manifestPath: String = "manga/${UUID.randomUUID()}.json",
    ) : LibraryVaultActiveTarget

    data class Created(
        override val mangaIdentity: String,
        val manifestPath: String,
    ) : LibraryVaultActiveTarget
}

internal data class LibraryVaultChapterPublishResult(
    val target: LibraryVaultActiveTarget,
    val mangaIdentity: VaultIdentity,
    val replaced: Boolean,
)

internal class LibraryCaptureGlobalFailure(
    val category: String,
    val chapterTitle: String?,
) : RuntimeException(category)
