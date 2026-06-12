package eu.kanade.tachiyomi.data.vault

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.importing.childPath
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
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestChapterProvenance
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultManifestProvenance
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.File
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
    private val publishTransaction = VaultManifestPublishTransaction(json)
    private val contentUploader = VaultContentUploader()

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
        val publishContext = publishTransaction.prepare(
            storage = webDav,
            config = config,
            target = target.toManifestPublishTarget(),
            expectedVaultIdentity = expectedVaultIdentity,
        ) { category ->
            LibraryCaptureGlobalFailure(category, chapter.name)
        }
        val rootManifest = publishContext.rootManifest
        val mangaManifestPath = publishContext.mangaManifestPath
        val remoteMangaManifest = publishContext.remoteMangaManifest
        val mangaIdentity = publishContext.mangaIdentity
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
            contentPath = contentUploader.uploadChapterFile(
                storage = webDav,
                config = config,
                mangaIdentity = mangaIdentity,
                contentIdentity = contentIdentity,
                chapterFile = stagedChapter.file,
                remoteFileName = "$contentIdentity.cbz",
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
                stager.findCaptureCover(manga, source)?.let { cover ->
                    contentUploader.uploadCover(
                        storage = webDav,
                        config = config,
                        mangaIdentity = mangaIdentity,
                        cover = VaultUploadCover.Bytes(
                            bytes = cover.bytes,
                            extension = cover.extension,
                            mediaType = cover.mediaType,
                        ),
                        now = now,
                    )
                }
            }.onSuccess { cover ->
                if (cover != null) {
                    newCoverPath = cover.path
                }
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
                newContentPath = contentPath,
                newCoverPath = newCoverPath,
            ) { category ->
                LibraryCaptureGlobalFailure(category, chapter.name)
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
            if (error is VaultContentUploadFailure) {
                error(error.category)
            }
            throw error
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

    private fun LibraryVaultActiveTarget.toManifestPublishTarget() = when (this) {
        is LibraryVaultActiveTarget.Existing -> VaultManifestPublishTarget.Existing(manga.identity.value)
        is LibraryVaultActiveTarget.CreateNew -> VaultManifestPublishTarget.CreateNew(mangaIdentity, manifestPath)
        is LibraryVaultActiveTarget.Created -> VaultManifestPublishTarget.Created(mangaIdentity, manifestPath)
    }
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
