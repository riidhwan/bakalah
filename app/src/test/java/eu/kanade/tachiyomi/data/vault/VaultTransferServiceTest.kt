package eu.kanade.tachiyomi.data.vault

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultCatalogueRefresh
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
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

class VaultTransferServiceTest {

    @Test
    fun `upload promotes only verified staged content`() = runTest {
        val repository = FakeVaultRepository()
        val remote = FakeTransferStorage()
        val local = FakeLocalStaging(mutableMapOf("source.cbz" to CONTENT))
        val service = VaultTransferService(repository, remote, local) { 10 }
        val integrity = CONTENT.vaultTransferIntegrity()
        val jobId = service.enqueue(
            vaultId = 1,
            type = VaultTransferType.IMPORT_PUBLISH,
            remotePath = "content/chapter.cbz",
            localPath = "source.cbz",
            sizeBytes = integrity.sizeBytes,
            checksumSha256 = integrity.checksumSha256,
        )

        service.execute(jobId) shouldBe VaultTransferResult.Succeeded

        remote.files["content/chapter.cbz"] shouldBe CONTENT
        remote.files.keys.any { it.contains("staged") } shouldBe false
        repository.transferJobs[jobId]?.state shouldBe VaultTransferState.SUCCEEDED
    }

    @Test
    fun `checksum mismatch keeps failed download out of cache`() = runTest {
        val repository = FakeVaultRepository()
        val remote = FakeTransferStorage(mutableMapOf("content/chapter.cbz" to CONTENT))
        val local = FakeLocalStaging()
        val service = VaultTransferService(repository, remote, local) { 10 }
        val jobId = service.enqueue(
            vaultId = 1,
            type = VaultTransferType.CACHE_CHAPTER,
            chapterId = 7,
            remotePath = "content/chapter.cbz",
            localPath = "cache/chapter.cbz",
            sizeBytes = CONTENT.size.toLong(),
            checksumSha256 = "wrong",
        )

        val result = service.execute(jobId)

        (result is VaultTransferResult.IntegrityFault) shouldBe true
        local.files["cache/chapter.cbz"] shouldBe null
        repository.cacheStates[7]?.state shouldBe VaultCacheState.INTEGRITY_FAULT
        repository.transferJobs[jobId]?.state shouldBe VaultTransferState.INTEGRITY_FAULT
    }

    @Test
    fun `failed staged download cleans up staged artifact and remains retryable`() = runTest {
        val repository = FakeVaultRepository()
        val remote = FakeTransferStorage(mutableMapOf("content/chapter.cbz" to CONTENT))
        val local = FakeLocalStaging(failPromote = true)
        val service = VaultTransferService(repository, remote, local) { 10 }
        val integrity = CONTENT.vaultTransferIntegrity()
        val jobId = service.enqueue(
            vaultId = 1,
            type = VaultTransferType.CACHE_CHAPTER,
            chapterId = 7,
            remotePath = "content/chapter.cbz",
            localPath = "cache/chapter.cbz",
            sizeBytes = integrity.sizeBytes,
            checksumSha256 = integrity.checksumSha256,
        )

        val firstResult = service.execute(jobId)
        local.failPromote = false
        val retryResult = service.retry(jobId)

        (firstResult is VaultTransferResult.Failed) shouldBe true
        retryResult shouldBe VaultTransferResult.Succeeded
        local.files.keys.any { it.contains("staged") } shouldBe false
        local.files["cache/chapter.cbz"] shouldBe CONTENT
    }

    @Test
    fun `cancelled download clears in progress cache state`() = runTest {
        val repository = FakeVaultRepository()
        val remote = FakeTransferStorage(mutableMapOf("content/chapter.cbz" to CONTENT))
        val local = FakeLocalStaging(writeError = CancellationException("cache cancelled"))
        val service = VaultTransferService(repository, remote, local) { 10 }
        val integrity = CONTENT.vaultTransferIntegrity()
        val jobId = service.enqueue(
            vaultId = 1,
            type = VaultTransferType.CACHE_CHAPTER,
            chapterId = 7,
            remotePath = "content/chapter.cbz",
            localPath = "cache/chapter.cbz",
            sizeBytes = integrity.sizeBytes,
            checksumSha256 = integrity.checksumSha256,
        )

        try {
            service.execute(jobId)
        } catch (_: CancellationException) {
        }

        repository.cacheStates[7]?.state shouldBe VaultCacheState.VAULT_ONLY
        repository.cacheStates[7]?.localPath shouldBe null
        repository.transferJobs[jobId]?.state shouldBe VaultTransferState.CANCELLED
    }

    @Test
    fun `cancel cleans staged artifact without rolling back completed jobs`() = runTest {
        val repository = FakeVaultRepository()
        val remote = FakeTransferStorage(mutableMapOf("remote.stage" to CONTENT))
        val local = FakeLocalStaging(mutableMapOf("local.stage" to CONTENT))
        val service = VaultTransferService(repository, remote, local) { 10 }
        val jobId = repository.upsertTransferJob(
            transferJob(
                state = VaultTransferState.RUNNING,
                stagedPath = "remote.stage",
            ),
        )

        service.cancel(jobId) shouldBe VaultTransferResult.Cancelled

        remote.files["remote.stage"] shouldBe null
        repository.transferJobs[jobId]?.state shouldBe VaultTransferState.CANCELLED

        repository.upsertTransferJob(repository.transferJobs[jobId]!!.copy(state = VaultTransferState.SUCCEEDED))
        service.cancel(jobId) shouldBe VaultTransferResult.AlreadyFinished(VaultTransferState.SUCCEEDED)
        repository.transferJobs[jobId]?.state shouldBe VaultTransferState.SUCCEEDED
    }

    private class FakeTransferStorage(
        val files: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : VaultTransferStorage {
        override suspend fun get(path: String): ByteArray? = files[path]
        override suspend fun put(path: String, bytes: ByteArray) {
            files[path] = bytes
        }
        override suspend fun promote(stagedPath: String, finalPath: String) {
            files[finalPath] = files.remove(stagedPath) ?: error("missing staged remote")
        }
        override suspend fun delete(path: String) {
            files.remove(path)
        }
    }

    private class FakeLocalStaging(
        val files: MutableMap<String, ByteArray> = mutableMapOf(),
        var failPromote: Boolean = false,
        val writeError: Throwable? = null,
    ) : VaultTransferLocalStaging {
        override suspend fun read(path: String): ByteArray? = files[path]
        override suspend fun write(path: String, bytes: ByteArray) {
            writeError?.let { throw it }
            files[path] = bytes
        }
        override suspend fun promote(stagedPath: String, finalPath: String) {
            if (failPromote) error("local promote failed")
            files[finalPath] = files.remove(stagedPath) ?: error("missing staged local")
        }
        override suspend fun delete(path: String) {
            files.remove(path)
        }
    }

    private class FakeVaultRepository : VaultRepository {
        val transferJobs = mutableMapOf<Long, VaultTransferJob>()
        val cacheStates = mutableMapOf<Long, VaultChapterCacheState>()
        private var nextId = 1L

        override fun getVaultsAsFlow(): Flow<List<ContentVault>> = emptyFlow()
        override suspend fun getVaultByIdentity(identity: ContentVaultIdentity): ContentVault? = null
        override suspend fun upsertVault(vault: ContentVault): Long = unsupported()
        override fun getMangaAsFlow(vaultId: Long): Flow<List<VaultManga>> = emptyFlow()
        override suspend fun getManga(vaultId: Long): List<VaultManga> = emptyList()
        override suspend fun getMangaById(id: Long): VaultManga? = null
        override suspend fun getMangaByIdentity(vaultId: Long, identity: VaultIdentity): VaultManga? = null
        override suspend fun upsertManga(manga: VaultManga): Long = unsupported()
        override fun getChaptersAsFlow(mangaId: Long): Flow<List<VaultChapter>> = emptyFlow()
        override fun getChaptersForVaultAsFlow(vaultId: Long): Flow<List<VaultChapter>> = emptyFlow()
        override suspend fun getChaptersForVault(vaultId: Long): List<VaultChapter> = emptyList()
        override suspend fun getChapters(mangaId: Long): List<VaultChapter> = emptyList()
        override suspend fun upsertChapters(mangaId: Long, chapters: List<VaultChapter>) = Unit
        override suspend fun getLabels(vaultId: Long): List<VaultLabel> = emptyList()
        override fun getLabelsAsFlow(vaultId: Long): Flow<List<VaultLabel>> = emptyFlow()
        override suspend fun getLabelsForManga(mangaId: Long): List<VaultLabel> = emptyList()
        override fun getLabelsByMangaForVaultAsFlow(vaultId: Long): Flow<Map<Long, List<VaultLabel>>> = emptyFlow()
        override suspend fun upsertLabels(vaultId: Long, labels: List<VaultLabel>) = Unit
        override suspend fun setMangaLabels(mangaId: Long, labelIds: List<Long>) = Unit
        override suspend fun getCoverForManga(mangaId: Long): VaultCover? = null
        override suspend fun upsertCover(cover: VaultCover): Long = unsupported()
        override suspend fun upsertReadingState(state: VaultReadingState) = Unit
        override suspend fun getReadingState(chapterId: Long): VaultReadingState? = null
        override suspend fun upsertCacheState(state: VaultChapterCacheState) {
            cacheStates[state.chapterId] = state
        }
        override suspend fun getCacheState(chapterId: Long): VaultChapterCacheState? = cacheStates[chapterId]
        override suspend fun deleteCacheStates(chapterIds: List<Long>) {
            chapterIds.forEach(cacheStates::remove)
        }
        override fun getCacheStatesForMangaAsFlow(mangaId: Long): Flow<List<VaultChapterCacheState>> = emptyFlow()
        override fun getCacheStatesForVaultAsFlow(vaultId: Long): Flow<List<VaultChapterCacheState>> = emptyFlow()
        override suspend fun getCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> = emptyList()
        override suspend fun getReadCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> = emptyList()
        override suspend fun getLocalCacheUsageBytes(vaultId: Long): Long = 0
        override suspend fun upsertImportTargetHint(hint: ImportTargetHint) = Unit
        override suspend fun getImportTargetHint(localMangaId: Long): ImportTargetHint? = null
        override fun getImportTargetHintAsFlow(localMangaId: Long): Flow<ImportTargetHint?> = emptyFlow()
        override suspend fun deleteImportTargetHint(localMangaId: Long) = Unit
        override suspend fun insertImportRequest(request: VaultImportRequest): Long = unsupported()
        override suspend fun getImportRequest(id: Long): VaultImportRequest? = null
        override suspend fun deleteImportRequest(id: Long) = Unit
        override suspend fun upsertManifestSnapshot(snapshot: VaultManifestSnapshot): Long = unsupported()
        override suspend fun refreshCatalogue(refresh: VaultCatalogueRefresh): Long = unsupported()
        override suspend fun deleteMangaLocalState(mangaId: Long) = Unit
        override fun getTransferJobsForVaultAsFlow(vaultId: Long): Flow<List<VaultTransferJob>> = emptyFlow()
        override suspend fun getTransferJobsForVault(vaultId: Long): List<VaultTransferJob> {
            return transferJobs.values.filter { it.vaultId == vaultId }
        }
        override suspend fun getTransferJobsByState(states: List<VaultTransferState>): List<VaultTransferJob> {
            return transferJobs.values.filter { it.state in states }
        }
        override suspend fun getTransferJob(id: Long): VaultTransferJob? = transferJobs[id]
        override suspend fun upsertTransferJob(job: VaultTransferJob): Long {
            val id = job.id.takeIf { it != -1L } ?: nextId++
            transferJobs[id] = job.copy(id = id)
            return id
        }

        private fun unsupported(): Nothing = error("Not used by this test")
    }

    private companion object {
        val CONTENT = byteArrayOf(1, 2, 3, 4)

        fun transferJob(
            state: VaultTransferState,
            stagedPath: String?,
        ) = VaultTransferJob(
            id = -1,
            vaultId = 1,
            chapterId = null,
            type = VaultTransferType.IMPORT_PUBLISH,
            state = state,
            remotePath = "remote",
            localPath = "local",
            stagedPath = stagedPath,
            sizeBytes = null,
            checksumSha256 = null,
            failureReason = null,
            attempts = 0,
            createdAt = 1,
            updatedAt = 1,
            startedAt = null,
            completedAt = null,
        )
    }
}
