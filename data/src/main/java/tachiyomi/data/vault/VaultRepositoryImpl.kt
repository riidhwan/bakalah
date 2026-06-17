package tachiyomi.data.vault

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultCatalogueRefresh
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterThumbnail
import tachiyomi.domain.vault.model.VaultCover
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.repository.VaultRepository

class VaultRepositoryImpl(
    private val database: Database,
) : VaultRepository {

    override fun getVaultsAsFlow(): Flow<List<ContentVault>> {
        return database.vaultQueries
            .getVaults(VaultMapper::mapVault)
            .subscribeToList()
    }

    override suspend fun getVaultByIdentity(identity: ContentVaultIdentity): ContentVault? {
        return database.vaultQueries
            .getVaultByIdentity(identity.value, VaultMapper::mapVault)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertVault(vault: ContentVault): Long {
        database.vaultQueries.upsertVault(
            id = vault.id,
            identity = vault.identity.value,
            displayName = vault.displayName,
            layoutVersion = vault.layoutVersion,
            rootRevisionId = vault.rootRevision.id,
            rootRevisionNumber = vault.rootRevision.number,
            writerId = vault.writerId,
            lastCatalogueRefreshAt = vault.lastCatalogueRefreshAt,
            createdAt = vault.createdAt,
            updatedAt = vault.updatedAt,
        )
        return database.vaultQueries
            .getVaultByIdentity(vault.identity.value)
            .awaitAsOne()
            ._id
    }

    override fun getMangaAsFlow(vaultId: Long): Flow<List<VaultManga>> {
        return database.vaultQueries
            .getMangaForVault(vaultId, VaultMapper::mapManga)
            .subscribeToList()
    }

    override suspend fun getManga(vaultId: Long): List<VaultManga> {
        return database.vaultQueries
            .getMangaForVault(vaultId, VaultMapper::mapManga)
            .awaitAsList()
    }

    override suspend fun getMangaById(id: Long): VaultManga? {
        return database.vaultQueries
            .getMangaById(id, VaultMapper::mapManga)
            .awaitAsOneOrNull()
    }

    override suspend fun getMangaByIdentity(vaultId: Long, identity: VaultIdentity): VaultManga? {
        return database.vaultQueries
            .getMangaByIdentity(vaultId, identity.value, VaultMapper::mapManga)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertManga(manga: VaultManga): Long {
        database.vaultQueries.upsertManga(
            id = manga.id,
            vaultId = manga.vaultId,
            identity = manga.identity.value,
            title = manga.metadata.title,
            sortKey = manga.sortKey,
            author = manga.metadata.author,
            artist = manga.metadata.artist,
            description = manga.metadata.description,
            status = manga.metadata.status,
            coverId = manga.coverId,
            revisionId = manga.revision.id,
            revisionNumber = manga.revision.number,
            createdAt = manga.createdAt,
            updatedAt = manga.updatedAt,
        )
        return database.vaultQueries
            .getMangaByIdentity(manga.vaultId, manga.identity.value)
            .awaitAsOne()
            ._id
    }

    override fun getChaptersAsFlow(mangaId: Long): Flow<List<VaultChapter>> {
        return database.vaultQueries
            .getChaptersForManga(mangaId, VaultMapper::mapChapter)
            .subscribeToList()
    }

    override fun getChaptersForVaultAsFlow(vaultId: Long): Flow<List<VaultChapter>> {
        return database.vaultQueries
            .getChaptersForVault(vaultId, VaultMapper::mapChapter)
            .subscribeToList()
    }

    override suspend fun getChaptersForVault(vaultId: Long): List<VaultChapter> {
        return database.vaultQueries
            .getChaptersForVault(vaultId, VaultMapper::mapChapter)
            .awaitAsList()
    }

    override suspend fun getChapters(mangaId: Long): List<VaultChapter> {
        return database.vaultQueries
            .getChaptersForManga(mangaId, VaultMapper::mapChapter)
            .awaitAsList()
    }

    override suspend fun upsertChapters(mangaId: Long, chapters: List<VaultChapter>) {
        database.transaction {
            if (chapters.isEmpty()) {
                database.vaultQueries.deleteChaptersForManga(mangaId)
            } else {
                database.vaultQueries.deleteChaptersNotInIdentities(mangaId, chapters.map { it.identity.value })
                chapters.forEach { chapter ->
                    upsertChapter(mangaId, chapter)
                }
            }
        }
    }

    override suspend fun getLabels(vaultId: Long): List<VaultLabel> {
        return database.vaultQueries
            .getLabels(vaultId, VaultMapper::mapLabel)
            .awaitAsList()
    }

    override fun getLabelsAsFlow(vaultId: Long): Flow<List<VaultLabel>> {
        return database.vaultQueries
            .getLabelsForVault(vaultId, VaultMapper::mapLabel)
            .subscribeToList()
    }

    override suspend fun getLabelsForManga(mangaId: Long): List<VaultLabel> {
        return database.vaultQueries
            .getLabelsForManga(mangaId, VaultMapper::mapLabel)
            .awaitAsList()
    }

    override fun getLabelsByMangaForVaultAsFlow(vaultId: Long): Flow<Map<Long, List<VaultLabel>>> {
        return database.vaultQueries
            .getLabelsByMangaForVault(vaultId) {
                    mangaId,
                    id,
                    labelVaultId,
                    identity,
                    name,
                    sortKey,
                    isSensitive,
                    createdAt,
                    updatedAt,
                ->
                mangaId to VaultMapper.mapLabel(
                    id = id,
                    vaultId = labelVaultId,
                    identity = identity,
                    name = name,
                    sortKey = sortKey,
                    isSensitive = isSensitive,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
            }
            .subscribeToList()
            .map { rows ->
                rows.groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
            }
    }

    override suspend fun upsertLabels(vaultId: Long, labels: List<VaultLabel>) {
        database.transaction {
            if (labels.isEmpty()) {
                database.vaultQueries.deleteLabelsForVault(vaultId)
            } else {
                database.vaultQueries.deleteLabelsNotInIdentities(vaultId, labels.map { it.identity.value })
                labels.forEach { label ->
                    database.vaultQueries.upsertLabel(
                        id = label.id,
                        vaultId = vaultId,
                        identity = label.identity.value,
                        name = label.name,
                        sortKey = label.sortKey,
                        isSensitive = label.isSensitive,
                        createdAt = label.createdAt,
                        updatedAt = label.updatedAt,
                    )
                }
            }
        }
    }

    override suspend fun setMangaLabels(mangaId: Long, labelIds: List<Long>) {
        database.transaction {
            database.vaultQueries.deleteMangaLabels(mangaId)
            labelIds.forEach { labelId ->
                database.vaultQueries.insertMangaLabel(mangaId, labelId)
            }
        }
    }

    override suspend fun upsertCover(cover: VaultCover): Long {
        database.vaultQueries.upsertCover(
            id = cover.id,
            mangaId = cover.mangaId,
            identity = cover.identity.value,
            path = cover.path,
            mediaType = cover.mediaType,
            sizeBytes = cover.sizeBytes,
            checksumSha256 = cover.checksumSha256,
            revisionId = cover.revision.id,
            revisionNumber = cover.revision.number,
            updatedAt = cover.updatedAt,
        )
        return database.vaultQueries
            .getCoverByIdentity(cover.mangaId, cover.identity.value)
            .awaitAsOne()
            ._id
    }

    override suspend fun getCoverForManga(mangaId: Long): VaultCover? {
        return database.vaultQueries
            .getCoverForManga(mangaId, VaultMapper::mapCover)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertReadingState(state: VaultReadingState) {
        database.vaultQueries.upsertReadingState(
            chapterId = state.chapterId,
            read = state.read,
            bookmark = state.bookmark,
            lastPageRead = state.lastPageRead,
            lastReadAt = state.lastReadAt,
            updatedAt = state.updatedAt,
        )
    }

    override suspend fun getReadingState(chapterId: Long): VaultReadingState? {
        return database.vaultQueries
            .getReadingState(chapterId, VaultMapper::mapReadingState)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertCacheState(state: VaultChapterCacheState) {
        database.vaultQueries.upsertCacheState(
            chapterId = state.chapterId,
            state = state.state,
            localPath = state.localPath,
            sizeBytes = state.sizeBytes,
            checksumSha256 = state.checksumSha256,
            lastVerifiedAt = state.lastVerifiedAt,
            lastOpenedAt = state.lastOpenedAt,
            updatedAt = state.updatedAt,
            failureReason = state.failureReason,
        )
    }

    override suspend fun getCacheState(chapterId: Long): VaultChapterCacheState? {
        return database.vaultQueries
            .getCacheState(chapterId, VaultMapper::mapCacheState)
            .awaitAsOneOrNull()
    }

    override suspend fun deleteCacheStates(chapterIds: List<Long>) {
        if (chapterIds.isNotEmpty()) {
            database.vaultQueries.deleteCacheStates(chapterIds)
        }
    }

    override fun getCacheStatesForMangaAsFlow(mangaId: Long): Flow<List<VaultChapterCacheState>> {
        return database.vaultQueries
            .getCacheStatesForManga(mangaId, VaultMapper::mapCacheState)
            .subscribeToList()
    }

    override fun getCacheStatesForVaultAsFlow(vaultId: Long): Flow<List<VaultChapterCacheState>> {
        return database.vaultQueries
            .getCacheStatesForVault(vaultId, VaultMapper::mapCacheState)
            .subscribeToList()
    }

    override suspend fun getCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> {
        return database.vaultQueries
            .getCacheStatesForVault(vaultId, VaultMapper::mapCacheState)
            .awaitAsList()
    }

    override suspend fun getReadCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> {
        return database.vaultQueries
            .getReadCacheStatesForVault(
                vaultId,
                VaultCacheState.CACHED,
                VaultMapper::mapCacheState,
            )
            .awaitAsList()
    }

    override suspend fun getLocalCacheUsageBytes(vaultId: Long): Long {
        return database.vaultQueries
            .getLocalCacheUsageBytes(vaultId, VaultCacheState.CACHED)
            .awaitAsOne()
            .toLong()
    }

    override suspend fun upsertImportTargetHint(hint: ImportTargetHint) {
        database.vaultQueries.upsertImportTargetHint(
            localMangaId = hint.localMangaId,
            localMangaIdentity = hint.localMangaIdentity,
            contentVaultIdentity = hint.contentVaultIdentity?.value,
            sourceIdentity = hint.sourceIdentity,
            vaultMangaIdentity = hint.vaultMangaIdentity?.value,
            vaultMangaId = hint.vaultMangaId,
            updatedAt = hint.updatedAt,
        )
    }

    override suspend fun getImportTargetHint(localMangaId: Long): ImportTargetHint? {
        return database.vaultQueries
            .getImportTargetHint(localMangaId, VaultMapper::mapImportTargetHint)
            .awaitAsOneOrNull()
    }

    override fun getImportTargetHintAsFlow(localMangaId: Long): Flow<ImportTargetHint?> {
        return database.vaultQueries
            .getImportTargetHintAsFlow(localMangaId, VaultMapper::mapImportTargetHint)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
    }

    override suspend fun deleteImportTargetHint(localMangaId: Long) {
        database.vaultQueries.deleteImportTargetHint(localMangaId)
    }

    override suspend fun insertImportRequest(request: VaultImportRequest): Long {
        return database.transactionWithResult {
            database.vaultQueries.insertImportRequest(
                id = request.id,
                mangaId = request.mangaId,
                workflow = request.workflow.name,
                targetMangaId = request.targetMangaId,
                createNewTitle = request.createNewTitle,
                activeMangaIdentity = request.activeMangaIdentity?.value,
                activeManifestPath = request.activeManifestPath,
                createdAt = request.createdAt,
                updatedAt = request.updatedAt,
            )
            val requestId = if (request.id != -1L) {
                request.id
            } else {
                database.vaultQueries
                    .lastInsertedImportRequestId()
                    .awaitAsOne()
            }
            request.chapters.forEach { chapter ->
                database.vaultQueries.insertImportRequestChapter(
                    requestId = requestId,
                    chapterId = chapter.chapterId,
                    selectionId = chapter.selectionId,
                    sortOrder = chapter.sortOrder,
                    allowReplacement = chapter.allowReplacement,
                    state = chapter.state.storageValue,
                    isReplaced = chapter.isReplaced,
                    failureCategory = chapter.failureCategory,
                    processedAt = chapter.processedAt,
                )
            }
            requestId
        }
    }

    override suspend fun getImportRequest(id: Long): VaultImportRequest? {
        val request = database.vaultQueries
            .getImportRequest(id)
            .awaitAsOneOrNull()
            ?: return null
        val chapters = database.vaultQueries
            .getImportRequestChapters(id, VaultMapper::mapImportRequestChapter)
            .awaitAsList()
        return VaultMapper.mapImportRequest(
            id = request._id,
            mangaId = request.manga_id,
            workflow = request.workflow,
            targetMangaId = request.target_manga_id,
            createNewTitle = request.create_new_title,
            activeMangaIdentity = request.active_manga_identity,
            activeManifestPath = request.active_manifest_path,
            createdAt = request.created_at,
            updatedAt = request.updated_at,
            chapters = chapters,
        )
    }

    override suspend fun updateImportRequestActiveTarget(
        id: Long,
        activeMangaIdentity: VaultIdentity,
        activeManifestPath: String,
        updatedAt: Long,
    ) {
        database.vaultQueries.updateImportRequestActiveTarget(
            id = id,
            activeMangaIdentity = activeMangaIdentity.value,
            activeManifestPath = activeManifestPath,
            updatedAt = updatedAt,
        )
    }

    override suspend fun markImportRequestChapterCompleted(
        requestId: Long,
        selectionId: String,
        isReplaced: Boolean,
        processedAt: Long,
    ) {
        database.vaultQueries.markImportRequestChapterCompleted(
            requestId = requestId,
            selectionId = selectionId,
            isReplaced = isReplaced,
            processedAt = processedAt,
        )
    }

    override suspend fun markImportRequestChapterFailed(
        requestId: Long,
        selectionId: String,
        failureCategory: String,
        processedAt: Long,
    ) {
        database.vaultQueries.markImportRequestChapterFailed(
            requestId = requestId,
            selectionId = selectionId,
            failureCategory = failureCategory,
            processedAt = processedAt,
        )
    }

    override suspend fun deleteImportRequest(id: Long) {
        database.vaultQueries.deleteImportRequest(id)
    }

    override suspend fun upsertManifestSnapshot(snapshot: VaultManifestSnapshot): Long {
        database.vaultQueries.upsertManifestSnapshot(
            id = snapshot.id,
            vaultId = snapshot.vaultId,
            mangaId = snapshot.mangaId,
            manifestPath = snapshot.manifestPath,
            revisionId = snapshot.revision.id,
            revisionNumber = snapshot.revision.number,
            body = snapshot.body,
            fetchedAt = snapshot.fetchedAt,
        )
        return database.vaultQueries
            .getManifestSnapshot(snapshot.vaultId, snapshot.manifestPath, snapshot.revision.id)
            .awaitAsOne()
            ._id
    }

    override suspend fun refreshCatalogue(refresh: VaultCatalogueRefresh): Long {
        return database.transactionWithResult {
            database.vaultQueries.upsertVault(
                id = refresh.vault.id,
                identity = refresh.vault.identity.value,
                displayName = refresh.vault.displayName,
                layoutVersion = refresh.vault.layoutVersion,
                rootRevisionId = refresh.vault.rootRevision.id,
                rootRevisionNumber = refresh.vault.rootRevision.number,
                writerId = refresh.vault.writerId,
                lastCatalogueRefreshAt = refresh.vault.lastCatalogueRefreshAt,
                createdAt = refresh.vault.createdAt,
                updatedAt = refresh.vault.updatedAt,
            )
            val vaultId = database.vaultQueries
                .getVaultByIdentity(refresh.vault.identity.value)
                .awaitAsOne()
                ._id

            if (refresh.manga.isEmpty()) {
                database.vaultQueries.deleteMangaForVault(vaultId)
            } else {
                database.vaultQueries.deleteMangaNotInIdentities(
                    vaultId = vaultId,
                    identities = refresh.manga.map { it.manga.identity.value },
                )
            }

            if (refresh.labels.isEmpty()) {
                database.vaultQueries.deleteLabelsForVault(vaultId)
            } else {
                database.vaultQueries.deleteLabelsNotInIdentities(
                    vaultId = vaultId,
                    identities = refresh.labels.map { it.identity.value },
                )
                refresh.labels.forEach { label ->
                    database.vaultQueries.upsertLabel(
                        id = label.id,
                        vaultId = vaultId,
                        identity = label.identity.value,
                        name = label.name,
                        sortKey = label.sortKey,
                        isSensitive = label.isSensitive,
                        createdAt = label.createdAt,
                        updatedAt = label.updatedAt,
                    )
                }
            }
            val labelIdsByIdentity = database.vaultQueries
                .getLabels(vaultId, VaultMapper::mapLabel)
                .awaitAsList()
                .associate { it.identity to it.id }

            refresh.manga.forEach { mangaRefresh ->
                database.vaultQueries.upsertManga(
                    id = mangaRefresh.manga.id,
                    vaultId = vaultId,
                    identity = mangaRefresh.manga.identity.value,
                    title = mangaRefresh.manga.metadata.title,
                    sortKey = mangaRefresh.manga.sortKey,
                    author = mangaRefresh.manga.metadata.author,
                    artist = mangaRefresh.manga.metadata.artist,
                    description = mangaRefresh.manga.metadata.description,
                    status = mangaRefresh.manga.metadata.status,
                    coverId = null,
                    revisionId = mangaRefresh.manga.revision.id,
                    revisionNumber = mangaRefresh.manga.revision.number,
                    createdAt = mangaRefresh.manga.createdAt,
                    updatedAt = mangaRefresh.manga.updatedAt,
                )
                val mangaId = database.vaultQueries
                    .getMangaByIdentity(vaultId, mangaRefresh.manga.identity.value)
                    .awaitAsOne()
                    ._id

                val cover = mangaRefresh.cover
                if (cover == null) {
                    database.vaultQueries.deleteCoversForManga(mangaId)
                } else {
                    database.vaultQueries.deleteCoversNotInIdentities(
                        mangaId = mangaId,
                        identities = listOf(cover.identity.value),
                    )
                }

                val coverId = cover?.let {
                    database.vaultQueries.upsertCover(
                        id = it.id,
                        mangaId = mangaId,
                        identity = it.identity.value,
                        path = it.path,
                        mediaType = it.mediaType,
                        sizeBytes = it.sizeBytes,
                        checksumSha256 = it.checksumSha256,
                        revisionId = it.revision.id,
                        revisionNumber = it.revision.number,
                        updatedAt = it.updatedAt,
                    )
                    database.vaultQueries
                        .getCoverByIdentity(mangaId, it.identity.value)
                        .awaitAsOne()
                        ._id
                }

                if (coverId != null) {
                    database.vaultQueries.upsertManga(
                        id = mangaId,
                        vaultId = vaultId,
                        identity = mangaRefresh.manga.identity.value,
                        title = mangaRefresh.manga.metadata.title,
                        sortKey = mangaRefresh.manga.sortKey,
                        author = mangaRefresh.manga.metadata.author,
                        artist = mangaRefresh.manga.metadata.artist,
                        description = mangaRefresh.manga.metadata.description,
                        status = mangaRefresh.manga.metadata.status,
                        coverId = coverId,
                        revisionId = mangaRefresh.manga.revision.id,
                        revisionNumber = mangaRefresh.manga.revision.number,
                        createdAt = mangaRefresh.manga.createdAt,
                        updatedAt = mangaRefresh.manga.updatedAt,
                    )
                }

                database.vaultQueries.deleteMangaLabels(mangaId)
                mangaRefresh.labelIdentities
                    .mapNotNull(labelIdsByIdentity::get)
                    .forEach { labelId ->
                        database.vaultQueries.insertMangaLabel(mangaId, labelId)
                    }

                if (mangaRefresh.chapters.isEmpty()) {
                    database.vaultQueries.deleteChaptersForManga(mangaId)
                } else {
                    database.vaultQueries.deleteChaptersNotInIdentities(
                        mangaId = mangaId,
                        identities = mangaRefresh.chapters.map { it.identity.value },
                    )
                    mangaRefresh.chapters.forEach { chapter ->
                        upsertChapter(mangaId, chapter)
                    }
                }

                refresh.snapshots
                    .filter { it.manifestPath == mangaRefresh.manifestPath }
                    .forEach { snapshot ->
                        database.vaultQueries.upsertManifestSnapshot(
                            id = snapshot.id,
                            vaultId = vaultId,
                            mangaId = mangaId,
                            manifestPath = snapshot.manifestPath,
                            revisionId = snapshot.revision.id,
                            revisionNumber = snapshot.revision.number,
                            body = snapshot.body,
                            fetchedAt = snapshot.fetchedAt,
                        )
                    }
            }

            refresh.snapshots
                .filter { it.mangaId == null }
                .forEach { snapshot ->
                    database.vaultQueries.upsertManifestSnapshot(
                        id = snapshot.id,
                        vaultId = vaultId,
                        mangaId = null,
                        manifestPath = snapshot.manifestPath,
                        revisionId = snapshot.revision.id,
                        revisionNumber = snapshot.revision.number,
                        body = snapshot.body,
                        fetchedAt = snapshot.fetchedAt,
                    )
                }

            vaultId
        }
    }

    override suspend fun deleteMangaLocalState(mangaId: Long) {
        database.transaction {
            database.vaultQueries.deleteTransferJobsForManga(mangaId)
            database.vaultQueries.deleteMangaById(mangaId)
        }
    }

    override fun getTransferJobsForVaultAsFlow(vaultId: Long): Flow<List<VaultTransferJob>> {
        return database.vaultQueries
            .getTransferJobsForVault(vaultId, VaultMapper::mapTransferJob)
            .subscribeToList()
    }

    override suspend fun getTransferJobsForVault(vaultId: Long): List<VaultTransferJob> {
        return database.vaultQueries
            .getTransferJobsForVault(vaultId, VaultMapper::mapTransferJob)
            .awaitAsList()
    }

    override suspend fun getTransferJobsByState(states: List<VaultTransferState>): List<VaultTransferJob> {
        if (states.isEmpty()) return emptyList()
        return database.vaultQueries
            .getTransferJobsByState(states, VaultMapper::mapTransferJob)
            .awaitAsList()
    }

    override suspend fun getTransferJob(id: Long): VaultTransferJob? {
        return database.vaultQueries
            .getTransferJob(id, VaultMapper::mapTransferJob)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertTransferJob(job: VaultTransferJob): Long {
        return database.transactionWithResult {
            database.vaultQueries.upsertTransferJob(
                id = job.id,
                vaultId = job.vaultId,
                chapterId = job.chapterId,
                importRequestId = job.importRequestId,
                type = job.type,
                state = job.state,
                remotePath = job.remotePath,
                localPath = job.localPath,
                stagedPath = job.stagedPath,
                sizeBytes = job.sizeBytes,
                checksumSha256 = job.checksumSha256,
                failureReason = job.failureReason,
                addedCount = job.addedCount,
                replacedCount = job.replacedCount,
                failedCount = job.failedCount,
                cancelledCount = job.cancelledCount,
                detailJson = job.detailJson,
                attempts = job.attempts,
                createdAt = job.createdAt,
                updatedAt = job.updatedAt,
                startedAt = job.startedAt,
                completedAt = job.completedAt,
            )
            if (job.id != -1L) {
                job.id
            } else {
                database.vaultQueries
                    .lastInsertedTransferJobId()
                    .awaitAsOne()
            }
        }
    }

    override suspend fun cancelInterruptedCaptureTransferJobsForImportRequest(
        importRequestId: Long,
        completedAt: Long,
    ) {
        database.vaultQueries.cancelInterruptedCaptureTransferJobsForImportRequest(
            importRequestId = importRequestId,
            type = VaultTransferType.CAPTURE_PUBLISH,
            runningState = VaultTransferState.RUNNING,
            state = VaultTransferState.CANCELLED,
            failureReason = "interrupted",
            updatedAt = completedAt,
            completedAt = completedAt,
        )
    }

    private suspend fun upsertChapter(mangaId: Long, chapter: VaultChapter): Long {
        database.vaultQueries.upsertChapter(
            id = chapter.id,
            mangaId = mangaId,
            identity = chapter.identity.value,
            title = chapter.title,
            chapterNumber = chapter.chapterNumber,
            volumeNumber = chapter.volumeNumber,
            scanlator = chapter.scanlator,
            sourceOrder = chapter.sourceOrder,
            contentPath = chapter.content.path,
            contentFormat = chapter.content.format,
            sizeBytes = chapter.content.sizeBytes,
            checksumSha256 = chapter.content.checksumSha256,
            revisionId = chapter.revision.id,
            revisionNumber = chapter.revision.number,
            dateUpload = chapter.dateUpload,
            createdAt = chapter.createdAt,
            updatedAt = chapter.updatedAt,
        )
        val chapterId = database.vaultQueries
            .getChapterByIdentity(mangaId, chapter.identity.value)
            .awaitAsOne()
            ._id
        syncChapterThumbnail(chapterId, chapter.thumbnail)
        return chapterId
    }

    private suspend fun syncChapterThumbnail(chapterId: Long, thumbnail: VaultChapterThumbnail?) {
        database.vaultQueries.updateChapterThumbnail(chapterId = chapterId, thumbnailId = null)
        if (thumbnail == null) {
            database.vaultQueries.deleteChapterThumbnailsForChapter(chapterId)
            return
        }

        database.vaultQueries.deleteChapterThumbnailsNotInIdentities(
            chapterId = chapterId,
            identities = listOf(thumbnail.identity.value),
        )
        database.vaultQueries.upsertChapterThumbnail(
            id = thumbnail.id,
            chapterId = chapterId,
            identity = thumbnail.identity.value,
            path = thumbnail.path,
            mediaType = thumbnail.mediaType,
            sizeBytes = thumbnail.sizeBytes,
            checksumSha256 = thumbnail.checksumSha256,
            revisionId = thumbnail.revision.id,
            revisionNumber = thumbnail.revision.number,
            updatedAt = thumbnail.updatedAt,
        )
        val thumbnailId = database.vaultQueries
            .getChapterThumbnailByIdentity(chapterId, thumbnail.identity.value)
            .awaitAsOne()
            ._id
        database.vaultQueries.updateChapterThumbnail(
            chapterId = chapterId,
            thumbnailId = thumbnailId,
        )
    }
}
