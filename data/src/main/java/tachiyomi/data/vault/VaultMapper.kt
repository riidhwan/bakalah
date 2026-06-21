package tachiyomi.data.vault

import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultChapterThumbnail
import tachiyomi.domain.vault.model.VaultCover
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultImportRequestChapterState
import tachiyomi.domain.vault.model.VaultImportRequestSummary
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType

object VaultMapper {
    fun mapVault(
        id: Long,
        identity: String,
        displayName: String,
        layoutVersion: Long,
        rootRevisionId: String,
        rootRevisionNumber: Long,
        writerId: String?,
        lastCatalogueRefreshAt: Long?,
        createdAt: Long,
        updatedAt: Long,
    ): ContentVault = ContentVault(
        id = id,
        identity = ContentVaultIdentity(identity),
        displayName = displayName,
        layoutVersion = layoutVersion,
        rootRevision = VaultRevision(rootRevisionId, rootRevisionNumber),
        writerId = writerId,
        lastCatalogueRefreshAt = lastCatalogueRefreshAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun mapManga(
        id: Long,
        vaultId: Long,
        identity: String,
        title: String,
        sortKey: String,
        author: String?,
        artist: String?,
        description: String?,
        status: VaultMangaStatus,
        coverId: Long?,
        revisionId: String,
        revisionNumber: Long,
        createdAt: Long,
        updatedAt: Long,
    ): VaultManga = VaultManga(
        id = id,
        vaultId = vaultId,
        identity = VaultIdentity(identity),
        metadata = VaultMetadata(
            title = title,
            author = author,
            artist = artist,
            description = description,
            status = status,
        ),
        sortKey = sortKey,
        coverId = coverId,
        revision = VaultRevision(revisionId, revisionNumber),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun mapChapter(
        id: Long,
        mangaId: Long,
        identity: String,
        title: String,
        chapterNumber: Double,
        volumeNumber: Double?,
        scanlator: String?,
        sourceOrder: Long,
        contentPath: String,
        contentFormat: VaultChapterContentFormat,
        sizeBytes: Long,
        checksumSha256: String,
        revisionId: String,
        revisionNumber: Long,
        dateUpload: Long,
        createdAt: Long,
        updatedAt: Long,
        thumbnailRowId: Long?,
        thumbnailIdentity: String?,
        thumbnailPath: String?,
        thumbnailMediaType: String?,
        thumbnailSizeBytes: Long?,
        thumbnailChecksumSha256: String?,
        thumbnailRevisionId: String?,
        thumbnailRevisionNumber: Long?,
        thumbnailUpdatedAt: Long?,
    ): VaultChapter = VaultChapter(
        id = id,
        mangaId = mangaId,
        identity = VaultIdentity(identity),
        title = title,
        chapterNumber = chapterNumber,
        volumeNumber = volumeNumber,
        scanlator = scanlator,
        sourceOrder = sourceOrder,
        content = VaultChapterContent(
            path = contentPath,
            format = contentFormat,
            sizeBytes = sizeBytes,
            checksumSha256 = checksumSha256,
        ),
        revision = VaultRevision(revisionId, revisionNumber),
        dateUpload = dateUpload,
        createdAt = createdAt,
        updatedAt = updatedAt,
        thumbnail = if (
            thumbnailIdentity != null &&
            thumbnailPath != null &&
            thumbnailRevisionId != null &&
            thumbnailRevisionNumber != null &&
            thumbnailUpdatedAt != null
        ) {
            VaultChapterThumbnail(
                id = thumbnailRowId ?: -1,
                chapterId = id,
                identity = VaultIdentity(thumbnailIdentity),
                path = thumbnailPath,
                mediaType = thumbnailMediaType,
                sizeBytes = thumbnailSizeBytes,
                checksumSha256 = thumbnailChecksumSha256,
                revision = VaultRevision(thumbnailRevisionId, thumbnailRevisionNumber),
                updatedAt = thumbnailUpdatedAt,
            )
        } else {
            null
        },
    )

    fun mapChapterThumbnail(
        id: Long,
        chapterId: Long,
        identity: String,
        path: String,
        mediaType: String?,
        sizeBytes: Long?,
        checksumSha256: String?,
        revisionId: String,
        revisionNumber: Long,
        updatedAt: Long,
    ): VaultChapterThumbnail = VaultChapterThumbnail(
        id = id,
        chapterId = chapterId,
        identity = VaultIdentity(identity),
        path = path,
        mediaType = mediaType,
        sizeBytes = sizeBytes,
        checksumSha256 = checksumSha256,
        revision = VaultRevision(revisionId, revisionNumber),
        updatedAt = updatedAt,
    )

    fun mapLabel(
        id: Long,
        vaultId: Long,
        identity: String,
        name: String,
        sortKey: String,
        isSensitive: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ): VaultLabel = VaultLabel(
        id = id,
        vaultId = vaultId,
        identity = VaultIdentity(identity),
        name = name,
        sortKey = sortKey,
        isSensitive = isSensitive,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun mapReadingState(
        chapterId: Long,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        lastReadAt: Long?,
        updatedAt: Long,
    ): VaultReadingState = VaultReadingState(
        chapterId = chapterId,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        lastReadAt = lastReadAt,
        updatedAt = updatedAt,
    )

    fun mapCacheState(
        chapterId: Long,
        state: VaultCacheState,
        localPath: String?,
        sizeBytes: Long?,
        checksumSha256: String?,
        lastVerifiedAt: Long?,
        lastOpenedAt: Long?,
        updatedAt: Long,
        failureReason: String?,
    ): VaultChapterCacheState = VaultChapterCacheState(
        chapterId = chapterId,
        state = state,
        localPath = localPath,
        sizeBytes = sizeBytes,
        checksumSha256 = checksumSha256,
        lastVerifiedAt = lastVerifiedAt,
        lastOpenedAt = lastOpenedAt,
        updatedAt = updatedAt,
        failureReason = failureReason,
    )

    fun mapImportTargetHint(
        localMangaId: Long,
        localMangaIdentity: String?,
        contentVaultIdentity: String?,
        sourceIdentity: String?,
        vaultMangaIdentity: String?,
        vaultMangaId: Long,
        updatedAt: Long,
    ): ImportTargetHint = ImportTargetHint(
        localMangaId = localMangaId,
        localMangaIdentity = localMangaIdentity,
        contentVaultIdentity = contentVaultIdentity?.let(::ContentVaultIdentity),
        sourceIdentity = sourceIdentity,
        vaultMangaIdentity = vaultMangaIdentity?.let(::VaultIdentity),
        vaultMangaId = vaultMangaId,
        updatedAt = updatedAt,
    )

    fun mapCover(
        id: Long,
        mangaId: Long,
        identity: String,
        path: String,
        mediaType: String?,
        sizeBytes: Long?,
        checksumSha256: String?,
        revisionId: String,
        revisionNumber: Long,
        updatedAt: Long,
    ): VaultCover = VaultCover(
        id = id,
        mangaId = mangaId,
        identity = VaultIdentity(identity),
        path = path,
        mediaType = mediaType,
        sizeBytes = sizeBytes,
        checksumSha256 = checksumSha256,
        revision = VaultRevision(revisionId, revisionNumber),
        updatedAt = updatedAt,
    )

    fun mapManifestSnapshot(
        id: Long,
        vaultId: Long,
        mangaId: Long?,
        manifestPath: String,
        revisionId: String,
        revisionNumber: Long,
        body: String,
        fetchedAt: Long,
    ): VaultManifestSnapshot = VaultManifestSnapshot(
        id = id,
        vaultId = vaultId,
        mangaId = mangaId,
        manifestPath = manifestPath,
        revision = VaultRevision(revisionId, revisionNumber),
        body = body,
        fetchedAt = fetchedAt,
    )

    fun mapImportRequest(
        id: Long,
        mangaId: Long,
        workflow: String,
        targetMangaId: Long?,
        createNewTitle: String?,
        activeMangaIdentity: String?,
        activeManifestPath: String?,
        createdAt: Long,
        updatedAt: Long,
        chapters: List<VaultImportRequestChapter>,
        sourceMangaTitle: String? = null,
        targetMangaTitle: String? = null,
    ): VaultImportRequest = VaultImportRequest(
        id = id,
        mangaId = mangaId,
        workflow = VaultImportRequestWorkflow.entries
            .firstOrNull { it.name == workflow }
            ?: VaultImportRequestWorkflow.LOCAL_IMPORT,
        targetMangaId = targetMangaId,
        createNewTitle = createNewTitle,
        activeMangaIdentity = activeMangaIdentity?.let(::VaultIdentity),
        activeManifestPath = activeManifestPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        chapters = chapters,
        sourceMangaTitle = sourceMangaTitle,
        targetMangaTitle = targetMangaTitle,
    )

    fun mapImportRequestSummary(
        id: Long,
        mangaId: Long,
        workflow: String,
        targetMangaId: Long?,
        createNewTitle: String?,
        activeMangaIdentity: String?,
        activeManifestPath: String?,
        createdAt: Long,
        updatedAt: Long,
        sourceMangaTitle: String?,
        sourceMangaSourceId: Long?,
        sourceMangaFavorite: Boolean?,
        sourceMangaThumbnailUrl: String?,
        sourceMangaCoverLastModified: Long?,
        targetMangaTitle: String?,
        isTargetSensitive: Boolean,
        totalChapters: Long,
        pendingChapters: Long,
        runningChapters: Long,
        completedChapters: Long,
        failedChapters: Long,
        replacedChapters: Long,
    ): VaultImportRequestSummary = VaultImportRequestSummary(
        id = id,
        mangaId = mangaId,
        workflow = VaultImportRequestWorkflow.entries
            .firstOrNull { it.name == workflow }
            ?: VaultImportRequestWorkflow.LOCAL_IMPORT,
        targetMangaId = targetMangaId,
        createNewTitle = createNewTitle,
        activeMangaIdentity = activeMangaIdentity?.let(::VaultIdentity),
        activeManifestPath = activeManifestPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        totalChapters = totalChapters.toInt(),
        pendingChapters = pendingChapters.toInt(),
        runningChapters = runningChapters.toInt(),
        completedChapters = completedChapters.toInt(),
        failedChapters = failedChapters.toInt(),
        replacedChapters = replacedChapters.toInt(),
        sourceMangaTitle = sourceMangaTitle,
        sourceMangaSourceId = sourceMangaSourceId,
        sourceMangaFavorite = sourceMangaFavorite ?: false,
        sourceMangaThumbnailUrl = sourceMangaThumbnailUrl,
        sourceMangaCoverLastModified = sourceMangaCoverLastModified ?: 0,
        targetMangaTitle = targetMangaTitle,
        isTargetSensitive = isTargetSensitive,
    )

    fun mapImportRequestChapter(
        requestId: Long,
        chapterId: Long?,
        selectionId: String,
        sortOrder: Long,
        allowReplacement: Boolean,
        state: String,
        isReplaced: Boolean,
        failureCategory: String?,
        processedAt: Long?,
        chapterTitle: String?,
    ): VaultImportRequestChapter = VaultImportRequestChapter(
        chapterId = chapterId,
        selectionId = selectionId,
        sortOrder = sortOrder,
        allowReplacement = allowReplacement,
        state = VaultImportRequestChapterState.fromStorageValue(state),
        isReplaced = isReplaced,
        failureCategory = failureCategory,
        processedAt = processedAt,
        chapterTitle = chapterTitle,
    )

    fun mapTransferJob(
        id: Long,
        vaultId: Long,
        mangaId: Long?,
        chapterId: Long?,
        importRequestId: Long?,
        operationKey: String?,
        operationQueueKey: String?,
        payloadJson: String?,
        type: VaultTransferType,
        state: VaultTransferState,
        remotePath: String?,
        localPath: String?,
        stagedPath: String?,
        sizeBytes: Long?,
        checksumSha256: String?,
        failureReason: String?,
        addedCount: Long,
        replacedCount: Long,
        failedCount: Long,
        cancelledCount: Long,
        detailJson: String?,
        attempts: Long,
        createdAt: Long,
        updatedAt: Long,
        startedAt: Long?,
        completedAt: Long?,
    ): VaultTransferJob = VaultTransferJob(
        id = id,
        vaultId = vaultId,
        mangaId = mangaId,
        chapterId = chapterId,
        importRequestId = importRequestId,
        operationKey = operationKey,
        operationQueueKey = operationQueueKey,
        payloadJson = payloadJson,
        type = type,
        state = state,
        remotePath = remotePath,
        localPath = localPath,
        stagedPath = stagedPath,
        sizeBytes = sizeBytes,
        checksumSha256 = checksumSha256,
        failureReason = failureReason,
        addedCount = addedCount,
        replacedCount = replacedCount,
        failedCount = failedCount,
        cancelledCount = cancelledCount,
        detailJson = detailJson,
        attempts = attempts,
        createdAt = createdAt,
        updatedAt = updatedAt,
        startedAt = startedAt,
        completedAt = completedAt,
    )
}
