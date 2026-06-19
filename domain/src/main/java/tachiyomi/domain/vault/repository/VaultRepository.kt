package tachiyomi.domain.vault.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultCatalogueRefresh
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultCover
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestSummary
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState

interface VaultRepository {

    fun getVaultsAsFlow(): Flow<List<ContentVault>>

    suspend fun getVaultByIdentity(identity: ContentVaultIdentity): ContentVault?

    suspend fun upsertVault(vault: ContentVault): Long

    fun getMangaAsFlow(vaultId: Long): Flow<List<VaultManga>>

    suspend fun getManga(vaultId: Long): List<VaultManga>

    suspend fun getMangaById(id: Long): VaultManga?

    suspend fun getMangaByIdentity(vaultId: Long, identity: VaultIdentity): VaultManga?

    suspend fun upsertManga(manga: VaultManga): Long

    fun getChaptersAsFlow(mangaId: Long): Flow<List<VaultChapter>>

    fun getChaptersForVaultAsFlow(vaultId: Long): Flow<List<VaultChapter>>

    suspend fun getChaptersForVault(vaultId: Long): List<VaultChapter>

    suspend fun getChapters(mangaId: Long): List<VaultChapter>

    suspend fun upsertChapters(mangaId: Long, chapters: List<VaultChapter>)

    suspend fun getLabels(vaultId: Long): List<VaultLabel>

    fun getLabelsAsFlow(vaultId: Long): Flow<List<VaultLabel>>

    suspend fun getLabelsForManga(mangaId: Long): List<VaultLabel>

    fun getLabelsByMangaForVaultAsFlow(vaultId: Long): Flow<Map<Long, List<VaultLabel>>>

    suspend fun upsertLabels(vaultId: Long, labels: List<VaultLabel>)

    suspend fun setMangaLabels(mangaId: Long, labelIds: List<Long>)

    suspend fun getCoverForManga(mangaId: Long): VaultCover?

    suspend fun upsertCover(cover: VaultCover): Long

    suspend fun upsertReadingState(state: VaultReadingState)

    suspend fun getReadingState(chapterId: Long): VaultReadingState?

    suspend fun upsertCacheState(state: VaultChapterCacheState)

    suspend fun getCacheState(chapterId: Long): VaultChapterCacheState?

    suspend fun deleteCacheStates(chapterIds: List<Long>)

    fun getCacheStatesForMangaAsFlow(mangaId: Long): Flow<List<VaultChapterCacheState>>

    fun getCacheStatesForVaultAsFlow(vaultId: Long): Flow<List<VaultChapterCacheState>>

    suspend fun getCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState>

    suspend fun getReadCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState>

    suspend fun getLocalCacheUsageBytes(vaultId: Long): Long

    suspend fun upsertImportTargetHint(hint: ImportTargetHint)

    suspend fun getImportTargetHint(localMangaId: Long): ImportTargetHint?

    fun getImportTargetHintAsFlow(localMangaId: Long): Flow<ImportTargetHint?>

    suspend fun deleteImportTargetHint(localMangaId: Long)

    suspend fun insertImportRequest(request: VaultImportRequest): Long

    suspend fun getImportRequest(id: Long): VaultImportRequest?

    fun getImportRequestSummariesAsFlow(): Flow<List<VaultImportRequestSummary>> =
        error("Not implemented")

    fun getImportRequestAsFlow(id: Long): Flow<VaultImportRequest?> =
        error("Not implemented")

    suspend fun updateImportRequestActiveTarget(
        id: Long,
        activeMangaIdentity: VaultIdentity,
        activeManifestPath: String,
        updatedAt: Long,
    )

    suspend fun resetRunningImportRequestChapters(
        requestId: Long,
    ): Unit = error("Not implemented")

    suspend fun markImportRequestChapterRunning(
        requestId: Long,
        selectionId: String,
        processedAt: Long,
    ): Unit = error("Not implemented")

    suspend fun markImportRequestChapterCompleted(
        requestId: Long,
        selectionId: String,
        isReplaced: Boolean,
        processedAt: Long,
    )

    suspend fun markImportRequestChapterFailed(
        requestId: Long,
        selectionId: String,
        failureCategory: String,
        processedAt: Long,
    )

    suspend fun markNonTerminalImportRequestChaptersFailed(
        requestId: Long,
        failureCategory: String,
        processedAt: Long,
    ): Unit = error("Not implemented")

    suspend fun deleteImportRequest(id: Long)

    suspend fun upsertManifestSnapshot(snapshot: VaultManifestSnapshot): Long

    suspend fun refreshCatalogue(refresh: VaultCatalogueRefresh): Long

    suspend fun deleteMangaLocalState(mangaId: Long)

    fun getTransferJobsForVaultAsFlow(vaultId: Long): Flow<List<VaultTransferJob>>

    suspend fun getTransferJobsForVault(vaultId: Long): List<VaultTransferJob>

    fun getTransferJobsForMangaAsFlow(mangaId: Long): Flow<List<VaultTransferJob>>

    suspend fun getActiveTransferJobsForOperationKey(operationKey: String): List<VaultTransferJob>

    suspend fun getTransferJobsByState(states: List<VaultTransferState>): List<VaultTransferJob>

    suspend fun getTransferJob(id: Long): VaultTransferJob?

    suspend fun upsertTransferJob(job: VaultTransferJob): Long

    suspend fun cancelInterruptedCaptureTransferJobsForImportRequest(importRequestId: Long, completedAt: Long)
}
