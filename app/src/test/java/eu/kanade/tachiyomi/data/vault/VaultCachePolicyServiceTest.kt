package eu.kanade.tachiyomi.data.vault

import io.kotest.matchers.collections.shouldContainExactly
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
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

class VaultCachePolicyServiceTest {

    @Test
    fun `cache path is rooted by vault manga and chapter identities`() {
        val service = VaultCachePolicyService(FakeVaultRepository(), FakeLocalStaging(), preferences())

        service.cachePath(manga(), chapter(id = 9, contentPath = "../content/chapter.cbz")) shouldBe
            "7/manga_one/chapter_9/chapter.cbz"
    }

    @Test
    fun `hard limit evicts oldest read cached chapters first`() = runTest {
        val repository = FakeVaultRepository()
        val local = FakeLocalStaging(
            mutableMapOf(
                "cache/old.cbz" to byteArrayOf(1),
                "cache/new.cbz" to byteArrayOf(2),
                "cache/unread.cbz" to byteArrayOf(3),
            ),
        )
        val preferences = preferences(limitBytes = 90)
        val service = VaultCachePolicyService(repository, local, preferences) { 100 }
        repository.cacheStates[1] = cacheState(chapterId = 1, localPath = "cache/old.cbz", sizeBytes = 60, openedAt = 1)
        repository.cacheStates[2] = cacheState(chapterId = 2, localPath = "cache/new.cbz", sizeBytes = 60, openedAt = 2)
        repository.cacheStates[3] =
            cacheState(chapterId = 3, localPath = "cache/unread.cbz", sizeBytes = 60, openedAt = 3)
        repository.readChapterIds += setOf(1, 2)

        val result = service.enforceLimit(vaultId = 7)

        result.evictedChapterIds.shouldContainExactly(1, 2)
        result.isOverLimit shouldBe false
        local.files.keys.shouldContainExactly("cache/unread.cbz")
        repository.cacheStates[1]?.state shouldBe VaultCacheState.VAULT_ONLY
        repository.cacheStates[2]?.state shouldBe VaultCacheState.VAULT_ONLY
        repository.cacheStates[3]?.state shouldBe VaultCacheState.CACHED
    }

    @Test
    fun `limit enforcement never evicts unread cached chapters`() = runTest {
        val repository = FakeVaultRepository()
        val local = FakeLocalStaging(mutableMapOf("cache/unread.cbz" to byteArrayOf(1)))
        val service = VaultCachePolicyService(repository, local, preferences(limitBytes = 10))
        repository.cacheStates[1] = cacheState(chapterId = 1, localPath = "cache/unread.cbz", sizeBytes = 60)

        val result = service.enforceLimit(vaultId = 7)

        result.evictedChapterIds shouldBe emptyList()
        result.isOverLimit shouldBe true
        local.files.keys.shouldContainExactly("cache/unread.cbz")
        repository.cacheStates[1]?.state shouldBe VaultCacheState.CACHED
    }

    @Test
    fun `limit enforcement keeps protected read cached chapters`() = runTest {
        val repository = FakeVaultRepository()
        val local = FakeLocalStaging(
            mutableMapOf(
                "cache/protected.cbz" to byteArrayOf(1),
                "cache/old.cbz" to byteArrayOf(2),
            ),
        )
        val service = VaultCachePolicyService(repository, local, preferences(limitBytes = 50))
        repository.cacheStates[1] = cacheState(chapterId = 1, localPath = "cache/protected.cbz", sizeBytes = 60)
        repository.cacheStates[2] = cacheState(chapterId = 2, localPath = "cache/old.cbz", sizeBytes = 60)
        repository.readChapterIds += setOf(1, 2)

        val result = service.enforceLimit(vaultId = 7, protectedChapterIds = setOf(1))

        result.evictedChapterIds.shouldContainExactly(2)
        local.files.keys.shouldContainExactly("cache/protected.cbz")
        repository.cacheStates[1]?.state shouldBe VaultCacheState.CACHED
        repository.cacheStates[2]?.state shouldBe VaultCacheState.VAULT_ONLY
    }

    @Test
    fun `manga eviction removes only cached chapter files tracked by cache state`() = runTest {
        val repository = FakeVaultRepository()
        val local = FakeLocalStaging(
            mutableMapOf(
                "cache/manga/chapter-1.cbz" to byteArrayOf(1),
                "local/original/chapter-1.cbz" to byteArrayOf(2),
                "downloads/chapter-1.cbz" to byteArrayOf(3),
            ),
        )
        val service = VaultCachePolicyService(repository, local, preferences()) { 100 }
        repository.chapters += chapter(id = 1, contentPath = "remote/chapter-1.cbz")
        repository.cacheStates[1] = cacheState(
            chapterId = 1,
            localPath = "cache/manga/chapter-1.cbz",
            sizeBytes = 60,
        )

        val result = service.evictManga(mangaId = 1)

        result.evictedChapterIds.shouldContainExactly(1)
        local.files.keys.shouldContainExactly(setOf("local/original/chapter-1.cbz", "downloads/chapter-1.cbz"))
        repository.cacheStates[1]?.state shouldBe VaultCacheState.VAULT_ONLY
        repository.cacheStates[1]?.localPath shouldBe null
    }

    private class FakeLocalStaging(
        val files: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : VaultTransferLocalStaging {
        override suspend fun read(path: String): ByteArray? = files[path]
        override suspend fun write(path: String, bytes: ByteArray) {
            files[path] = bytes
        }
        override suspend fun promote(stagedPath: String, finalPath: String) {
            files[finalPath] = files.remove(stagedPath) ?: error("missing staged local")
        }
        override suspend fun delete(path: String) {
            files.remove(path)
        }
    }

    private class FakeVaultRepository : VaultRepository {
        val cacheStates = mutableMapOf<Long, VaultChapterCacheState>()
        val chapters = mutableListOf<VaultChapter>()
        val readChapterIds = mutableSetOf<Long>()

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
        override suspend fun getChapters(mangaId: Long): List<VaultChapter> = chapters.filter { it.mangaId == mangaId }
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
        override suspend fun getCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> {
            return cacheStates.values.toList()
        }
        override suspend fun getReadCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> {
            return cacheStates.values
                .filter { it.chapterId in readChapterIds && it.state == VaultCacheState.CACHED }
                .sortedWith(
                    compareBy<VaultChapterCacheState> { it.lastOpenedAt ?: it.lastVerifiedAt ?: it.updatedAt }
                        .thenBy { it.chapterId },
                )
        }
        override suspend fun getLocalCacheUsageBytes(vaultId: Long): Long {
            return cacheStates.values
                .filter { it.state == VaultCacheState.CACHED }
                .sumOf { it.sizeBytes ?: 0L }
        }
        override suspend fun upsertImportTargetHint(hint: ImportTargetHint) = Unit
        override suspend fun getImportTargetHint(localMangaId: Long): ImportTargetHint? = null
        override fun getImportTargetHintAsFlow(localMangaId: Long): Flow<ImportTargetHint?> = emptyFlow()
        override suspend fun deleteImportTargetHint(localMangaId: Long) = Unit
        override suspend fun upsertManifestSnapshot(snapshot: VaultManifestSnapshot): Long = unsupported()
        override suspend fun refreshCatalogue(refresh: VaultCatalogueRefresh): Long = unsupported()
        override suspend fun deleteMangaLocalState(mangaId: Long) = Unit
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
        fun preferences(
            limitBytes: Long = ContentVaultPreferences.DEFAULT_LOCAL_CACHE_LIMIT_BYTES,
        ): ContentVaultPreferences {
            return ContentVaultPreferences(InMemoryPreferenceStore()).also {
                it.localCacheLimitBytes.set(limitBytes)
            }
        }

        fun manga() = VaultManga(
            id = 1,
            vaultId = 7,
            identity = VaultIdentity("manga/one"),
            metadata = VaultMetadata(
                title = "Manga",
                author = null,
                artist = null,
                description = null,
                status = VaultMangaStatus.UNKNOWN,
            ),
            sortKey = "manga",
            coverId = null,
            revision = VaultRevision("revision", 1),
            createdAt = 1,
            updatedAt = 1,
        )

        fun chapter(id: Long, contentPath: String) = VaultChapter(
            id = id,
            mangaId = 1,
            identity = VaultIdentity("chapter/$id"),
            title = "Chapter $id",
            chapterNumber = id.toDouble(),
            volumeNumber = null,
            scanlator = null,
            sourceOrder = id,
            content = VaultChapterContent(
                path = contentPath,
                format = VaultChapterContentFormat.CBZ,
                sizeBytes = 10,
                checksumSha256 = "checksum",
            ),
            revision = VaultRevision("revision", 1),
            dateUpload = 1,
            createdAt = 1,
            updatedAt = 1,
        )

        fun cacheState(
            chapterId: Long,
            localPath: String,
            sizeBytes: Long,
            openedAt: Long? = null,
        ) = VaultChapterCacheState(
            chapterId = chapterId,
            state = VaultCacheState.CACHED,
            localPath = localPath,
            sizeBytes = sizeBytes,
            checksumSha256 = "checksum",
            lastVerifiedAt = 1,
            lastOpenedAt = openedAt,
            updatedAt = 1,
            failureReason = null,
        )
    }
}
