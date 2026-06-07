package eu.kanade.tachiyomi.data.vault

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultCatalogueRefresh
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultCover
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaCollectionState
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

class VaultReaderOpenServiceTest {

    @Test
    fun `cached chapter opens after size and checksum verification`() = runTest {
        val content = byteArrayOf(1, 2, 3)
        val integrity = content.vaultTransferIntegrity()
        val repository = FakeVaultRepository()
        val local = FakeLocalStaging(mutableMapOf("cache/chapter.cbz" to content))
        repository.manga = manga()
        repository.chapters += chapter(sizeBytes = integrity.sizeBytes, checksumSha256 = integrity.checksumSha256)
        repository.cacheStates[1] = cacheState(
            localPath = "cache/chapter.cbz",
            sizeBytes = integrity.sizeBytes,
            checksumSha256 = integrity.checksumSha256,
        )
        val service = service(repository, local)

        val result = service.prepareChapter(mangaId = 1, chapterId = 1)

        result shouldBe
            VaultReaderOpenResult.Ready(repository.manga!!, repository.chapters.single(), "cache/chapter.cbz")
        repository.cacheStates[1]?.state shouldBe VaultCacheState.CACHED
        repository.cacheStates[1]?.lastVerifiedAt shouldBe 100
    }

    @Test
    fun `missing cached file is demoted to vault only and not opened`() = runTest {
        val repository = FakeVaultRepository()
        val local = FakeLocalStaging()
        repository.manga = manga()
        repository.chapters += chapter(sizeBytes = 3, checksumSha256 = "checksum")
        repository.cacheStates[1] =
            cacheState(localPath = "cache/missing.cbz", sizeBytes = 3, checksumSha256 = "checksum")
        val service = service(repository, local)

        val result = service.prepareChapter(mangaId = 1, chapterId = 1)

        result shouldBe VaultReaderOpenResult.Failed("incomplete configuration")
        repository.cacheStates[1]?.state shouldBe VaultCacheState.VAULT_ONLY
        repository.cacheStates[1]?.localPath shouldBe null
    }

    private fun service(
        repository: FakeVaultRepository,
        local: FakeLocalStaging,
    ) = VaultReaderOpenService(
        repository = repository,
        preferences = ContentVaultPreferences(InMemoryPreferenceStore()),
        transferServiceFactory = { null },
        localStaging = local,
        now = { 100 },
    )

    private class FakeLocalStaging(
        val files: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : VaultTransferLocalStaging {
        override suspend fun read(path: String): ByteArray? = files[path]
        override suspend fun write(path: String, bytes: ByteArray) {
            files[path] = bytes
        }
        override suspend fun promote(stagedPath: String, finalPath: String) = Unit
        override suspend fun delete(path: String) {
            files.remove(path)
        }
    }

    private class FakeVaultRepository : VaultRepository {
        var manga: VaultManga? = null
        val chapters = mutableListOf<VaultChapter>()
        val cacheStates = mutableMapOf<Long, VaultChapterCacheState>()

        override fun getVaultsAsFlow(): Flow<List<ContentVault>> = emptyFlow()
        override suspend fun getVaultByIdentity(identity: ContentVaultIdentity): ContentVault? = null
        override suspend fun upsertVault(vault: ContentVault): Long = unsupported()
        override fun getMangaAsFlow(vaultId: Long): Flow<List<VaultManga>> = emptyFlow()
        override suspend fun getManga(vaultId: Long): List<VaultManga> = manga?.let(::listOf).orEmpty()
        override suspend fun getMangaById(id: Long): VaultManga? = manga?.takeIf { it.id == id }
        override suspend fun getMangaByIdentity(vaultId: Long, identity: VaultIdentity): VaultManga? = null
        override suspend fun upsertManga(manga: VaultManga): Long = unsupported()
        override fun getChaptersAsFlow(mangaId: Long): Flow<List<VaultChapter>> = emptyFlow()
        override fun getChaptersForVaultAsFlow(vaultId: Long): Flow<List<VaultChapter>> = emptyFlow()
        override suspend fun getChaptersForVault(vaultId: Long): List<VaultChapter> = emptyList()
        override suspend fun getChapters(mangaId: Long): List<VaultChapter> = chapters.filter { it.mangaId == mangaId }
        override suspend fun upsertChapters(mangaId: Long, chapters: List<VaultChapter>) = Unit
        override suspend fun getLabels(vaultId: Long): List<VaultLabel> = emptyList()
        override suspend fun upsertLabels(vaultId: Long, labels: List<VaultLabel>) = Unit
        override suspend fun setMangaLabels(mangaId: Long, labelIds: List<Long>) = Unit
        override suspend fun upsertCover(cover: VaultCover): Long = unsupported()
        override suspend fun upsertReadingState(state: VaultReadingState) = Unit
        override suspend fun getReadingState(chapterId: Long): VaultReadingState? = null
        override suspend fun upsertCacheState(state: VaultChapterCacheState) {
            cacheStates[state.chapterId] = state
        }
        override suspend fun getCacheState(chapterId: Long): VaultChapterCacheState? = cacheStates[chapterId]
        override fun getCacheStatesForMangaAsFlow(mangaId: Long): Flow<List<VaultChapterCacheState>> = emptyFlow()
        override fun getCacheStatesForVaultAsFlow(vaultId: Long): Flow<List<VaultChapterCacheState>> = emptyFlow()
        override suspend fun getCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> = emptyList()
        override suspend fun getReadCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> = emptyList()
        override suspend fun getLocalCacheUsageBytes(vaultId: Long): Long = 0
        override suspend fun upsertImportTargetHint(hint: ImportTargetHint) = Unit
        override suspend fun getImportTargetHint(localMangaId: Long): ImportTargetHint? = null
        override suspend fun upsertManifestSnapshot(snapshot: VaultManifestSnapshot): Long = unsupported()
        override suspend fun refreshCatalogue(refresh: VaultCatalogueRefresh): Long = unsupported()
        override fun getTransferJobsForVaultAsFlow(vaultId: Long): Flow<List<VaultTransferJob>> = emptyFlow()
        override suspend fun getTransferJobsForVault(vaultId: Long): List<VaultTransferJob> = emptyList()
        override suspend fun getTransferJobsByState(
            states: List<VaultTransferState>,
        ): List<VaultTransferJob> = emptyList()
        override suspend fun getTransferJob(id: Long): VaultTransferJob? = null
        override suspend fun upsertTransferJob(job: VaultTransferJob): Long = unsupported()

        private fun unsupported(): Nothing = error("Not used by this test")
    }

    private companion object {
        fun manga() = VaultManga(
            id = 1,
            vaultId = 7,
            identity = VaultIdentity("manga"),
            metadata = VaultMetadata("Manga", null, null, null, VaultMangaStatus.UNKNOWN),
            sortKey = "manga",
            collectionState = VaultMangaCollectionState.ACTIVE,
            trashedAt = null,
            coverId = null,
            revision = VaultRevision("revision", 1),
            createdAt = 1,
            updatedAt = 1,
        )

        fun chapter(sizeBytes: Long, checksumSha256: String) = VaultChapter(
            id = 1,
            mangaId = 1,
            identity = VaultIdentity("chapter"),
            title = "Chapter",
            chapterNumber = 1.0,
            volumeNumber = null,
            scanlator = null,
            sourceOrder = 1,
            content = VaultChapterContent(
                "remote/chapter.cbz",
                VaultChapterContentFormat.CBZ,
                sizeBytes,
                checksumSha256,
            ),
            revision = VaultRevision("revision", 1),
            dateUpload = 1,
            createdAt = 1,
            updatedAt = 1,
        )

        fun cacheState(
            localPath: String,
            sizeBytes: Long,
            checksumSha256: String,
        ) = VaultChapterCacheState(
            chapterId = 1,
            state = VaultCacheState.CACHED,
            localPath = localPath,
            sizeBytes = sizeBytes,
            checksumSha256 = checksumSha256,
            lastVerifiedAt = 1,
            lastOpenedAt = null,
            updatedAt = 1,
            failureReason = null,
        )
    }
}
