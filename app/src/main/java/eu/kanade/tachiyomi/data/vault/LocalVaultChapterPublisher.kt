package eu.kanade.tachiyomi.data.vault

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.importing.LocalVaultChapterStager
import eu.kanade.tachiyomi.data.vault.importing.ScannedLocalVaultChapter
import eu.kanade.tachiyomi.data.vault.importing.childPath
import eu.kanade.tachiyomi.data.vault.importing.coverMediaType
import eu.kanade.tachiyomi.data.vault.importing.duplicateFileKey
import eu.kanade.tachiyomi.data.vault.importing.orderVaultImportChapters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import tachiyomi.core.common.storage.extension
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.LocalVaultImportManga
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultManifestProvenance
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
    private val publishTransaction = VaultManifestPublishTransaction(json)
    private val contentUploader = VaultContentUploader()

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
        val publishContext = publishTransaction.prepare(
            storage = webDav,
            config = config,
            target = target.toManifestPublishTarget(),
            expectedVaultIdentity = expectedVaultIdentity,
        ) { category ->
            LocalImportGlobalFailure(category, localChapter.chapter.title)
        }
        val rootManifest = publishContext.rootManifest
        val mangaManifestPath = publishContext.mangaManifestPath
        val remoteMangaManifest = publishContext.remoteMangaManifest
        val mangaIdentity = publishContext.mangaIdentity
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
        var contentUpload: VaultPromotableUpload? = null
        var promotedCoverPath: String? = null
        try {
            progressPhase(VaultImportProgressPhase.UPLOADING)
            val uploadedChapter = contentUploader.uploadChapterFile(
                storage = webDav,
                config = config,
                mangaIdentity = mangaIdentity,
                contentIdentity = contentIdentity,
                chapterFile = preparedChapter.file,
            )
            contentUpload = uploadedChapter
            val manifestChapter = if (replacement != null) {
                preparedChapter.toReplacementManifestChapter(
                    existing = replacement,
                    contentPath = uploadedChapter.finalPath,
                    now = now,
                )
            } else {
                preparedChapter.toManifestChapter(
                    identity = chapterIdentity,
                    contentPath = uploadedChapter.finalPath,
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
                coverFile?.let { file ->
                    contentUploader.uploadCover(
                        storage = webDav,
                        config = config,
                        mangaIdentity = mangaIdentity,
                        cover = VaultUploadCover.File(
                            file = file,
                            extension = file.extension,
                            mediaType = file.coverMediaType(),
                        ),
                        now = now,
                    )
                }
            }.onSuccess { uploadedCover ->
                if (uploadedCover != null) {
                    if (promoteOptionalUpload(webDav, config, uploadedCover.upload)) {
                        promotedCoverPath = uploadedCover.upload.finalPath
                    }
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }?.takeIf { promotedCoverPath == it.cover.path }?.cover
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

            progressPhase(VaultImportProgressPhase.PUBLISHING)
            publishTransaction.commit(
                storage = webDav,
                config = config,
                context = publishContext,
                metadata = metadata,
                mangaManifest = mangaManifest,
                mangaRevisionId = mangaRevisionId,
                mangaRevisionNumber = mangaRevision,
                now = now,
                newUploads = listOfNotNull(contentUpload),
                promotedPaths = listOfNotNull(promotedCoverPath),
            ) { category ->
                LocalImportGlobalFailure(category, localChapter.chapter.title)
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
            listOfNotNull(contentUpload).forEach { upload ->
                runCatching { webDav.delete(config.rootPath.childPath(upload.stagedPath)) }
                runCatching { webDav.delete(config.rootPath.childPath(upload.finalPath)) }
            }
            promotedCoverPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            if (error is VaultContentUploadFailure) {
                error(error.category)
            }
            throw error
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

    private suspend fun promoteOptionalUpload(
        webDav: VaultWebDav,
        config: WebDavVaultConfig,
        upload: VaultPromotableUpload,
    ): Boolean {
        return runCatching {
            webDav.promote(
                config.rootPath.childPath(upload.stagedPath),
                config.rootPath.childPath(upload.finalPath),
            )
        }.getOrDefault(false).also { promoted ->
            if (promoted) return@also
            runCatching { webDav.delete(config.rootPath.childPath(upload.stagedPath)) }
            runCatching { webDav.delete(config.rootPath.childPath(upload.finalPath)) }
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

    private fun LocalVaultActiveTarget.toManifestPublishTarget() = when (this) {
        is LocalVaultActiveTarget.Existing -> VaultManifestPublishTarget.Existing(manga.identity.value)
        is LocalVaultActiveTarget.CreateNew -> VaultManifestPublishTarget.CreateNew(mangaIdentity, manifestPath)
        is LocalVaultActiveTarget.Created -> VaultManifestPublishTarget.Created(mangaIdentity, manifestPath)
    }
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
