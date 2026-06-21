package eu.kanade.tachiyomi.data.vault.localimport

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.add.AddToVaultProgressPhase
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationQueueDrainer
import eu.kanade.tachiyomi.data.vault.publishing.VaultContentUploadFailure
import eu.kanade.tachiyomi.data.vault.publishing.VaultContentUploader
import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishGate
import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishTarget
import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishTransaction
import eu.kanade.tachiyomi.data.vault.publishing.VaultPromotableUpload
import eu.kanade.tachiyomi.data.vault.publishing.VaultUploadCover
import eu.kanade.tachiyomi.data.vault.publishing.VaultUploadedCover
import eu.kanade.tachiyomi.data.vault.remote.childPath
import eu.kanade.tachiyomi.data.vault.staging.coverMediaType
import eu.kanade.tachiyomi.data.vault.webdav.VaultWebDav
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
        progressPhase: (AddToVaultProgressPhase) -> Unit,
    ): LocalVaultChapterPublishResult
}

internal class LocalVaultChapterPublisher(
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val chapterStager: LocalVaultChapterStager,
    private val operationQueueDrainer: VaultOperationQueueDrainer,
    private val publishGate: VaultManifestPublishGate,
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
        progressPhase: (AddToVaultProgressPhase) -> Unit,
    ): LocalVaultChapterPublishResult {
        operationQueueDrainer.waitUntilDrained(vaultIdentity)
        val preflightContext = publishTransaction.prepare(
            storage = webDav,
            config = config,
            target = target.toManifestPublishTarget(),
            expectedVaultIdentity = expectedVaultIdentity,
        ) { category ->
            LocalImportGlobalFailure(category, localChapter.chapter.title)
        }
        val mangaIdentity = target.mangaIdentity ?: error("target")
        val shouldUploadInitialCover = preflightContext.remoteMangaManifest?.cover == null
        val now = System.currentTimeMillis()

        progressPhase(AddToVaultProgressPhase.COMPRESSING)
        val preparedChapter = chapterStager.stageForUpload(localChapter, stagingRoot)
        val newChapterIdentity = UUID.randomUUID().toString()
        var contentUpload: VaultPromotableUpload? = null
        var uploadedCover: VaultUploadedCover? = null
        var promotedCoverPath: String? = null
        try {
            progressPhase(AddToVaultProgressPhase.UPLOADING)
            val uploadedChapter = contentUploader.uploadChapterFile(
                storage = webDav,
                config = config,
                mangaIdentity = mangaIdentity,
                contentIdentity = newChapterIdentity,
                chapterFile = preparedChapter.file,
            )
            contentUpload = uploadedChapter
            uploadedCover = runCatching {
                progressPhase(AddToVaultProgressPhase.UPLOADING)
                coverFile?.takeIf { shouldUploadInitialCover }?.let { file ->
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
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }

            progressPhase(AddToVaultProgressPhase.PUBLISHING)
            operationQueueDrainer.waitUntilDrained(vaultIdentity)
            val committed = publishGate.withGate(vaultIdentity) {
                val publishContext = publishTransaction.prepare(
                    storage = webDav,
                    config = config,
                    target = target.toManifestPublishTarget(),
                    expectedVaultIdentity = expectedVaultIdentity,
                ) { category ->
                    LocalImportGlobalFailure(category, localChapter.chapter.title)
                }
                val remoteMangaManifest = publishContext.remoteMangaManifest
                val existingRemoteChapters = remoteMangaManifest?.chapters.orEmpty()
                val existingRemoteChaptersByFileKey = existingRemoteChapters
                    .associateBy { it.content.path.substringAfterLast('/').duplicateFileKey() }
                val replacement = existingRemoteChaptersByFileKey[
                    localChapter.chapter.sourceFileName.duplicateFileKey(),
                ]
                if (replacement != null && !allowReplacement) {
                    error("unconfirmed_duplicate")
                }

                val chapterIdentity = replacement?.identity ?: newChapterIdentity
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
                val importedCover = remoteMangaManifest?.cover ?: uploadedCover?.let { cover ->
                    promotedCoverPath = publishTransaction.promoteOptionalUpload(webDav, config, cover.upload)
                    cover.takeIf { promotedCoverPath == it.cover.path }?.cover
                }
                val mangaRevision = remoteMangaManifest?.revisionNumber?.plus(1) ?: 1
                val mangaRevisionId = UUID.randomUUID().toString()
                val mangaManifest = VaultMangaManifest(
                    layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
                    vaultIdentity = publishContext.rootManifest.identity,
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

                val nextTarget = when (target) {
                    is LocalVaultActiveTarget.Existing -> target
                    is LocalVaultActiveTarget.CreateNew,
                    is LocalVaultActiveTarget.Created,
                    -> LocalVaultActiveTarget.Created(
                        mangaIdentity = mangaIdentity,
                        manifestPath = publishContext.mangaManifestPath,
                    )
                }
                LocalVaultCommittedPublish(
                    result = LocalVaultChapterPublishResult(
                        target = nextTarget,
                        mangaIdentity = VaultIdentity(mangaIdentity),
                        replaced = replacement != null,
                    ),
                    replacedChapterIdentity = replacement?.identity,
                    replacedContentPath = replacement?.content?.path,
                )
            }

            committed.replacedContentPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            if (committed.replacedChapterIdentity != null) {
                invalidateReplacementCacheState(mangaIdentity, committed.replacedChapterIdentity)
            }
            if (uploadedCover != null && promotedCoverPath == null) {
                publishTransaction.cleanupUploadedContent(
                    storage = webDav,
                    config = config,
                    newUploads = listOf(uploadedCover.upload),
                )
            }
            return committed.result
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            listOfNotNull(contentUpload).forEach { upload ->
                runCatching { webDav.delete(config.rootPath.childPath(upload.stagedPath)) }
                runCatching { webDav.delete(config.rootPath.childPath(upload.finalPath)) }
            }
            uploadedCover?.upload?.let { upload ->
                runCatching { webDav.delete(config.rootPath.childPath(upload.stagedPath)) }
                runCatching { webDav.delete(config.rootPath.childPath(upload.finalPath)) }
            }
            promotedCoverPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            if (error is VaultContentUploadFailure) {
                error(error.category)
            }
            if (error is LocalImportGlobalFailure) throw error
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

    private data class LocalVaultCommittedPublish(
        val result: LocalVaultChapterPublishResult,
        val replacedChapterIdentity: String?,
        val replacedContentPath: String?,
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
