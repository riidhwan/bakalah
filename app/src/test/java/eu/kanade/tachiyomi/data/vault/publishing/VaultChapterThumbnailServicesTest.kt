package eu.kanade.tachiyomi.data.vault.publishing

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshResult
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import eu.kanade.tachiyomi.data.vault.transfer.vaultTransferIntegrity
import eu.kanade.tachiyomi.data.vault.webdav.VaultWebDav
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultChapterThumbnail
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultMangaManifestPointer
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultRootManifest
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

class VaultChapterThumbnailServicesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val codec = VaultManifestCodec(json)
    private val config = WebDavVaultConfig(
        serverUrl = "https://example.invalid",
        username = "user",
        password = "password",
        rootPath = "vault",
    )

    @Test
    fun `publish writes thumbnail content manifests cache and transfer job`() = runTest {
        val remote = FakeWebDav()
        remote.files["vault/$ROOT_VAULT_MANIFEST_NAME"] = codec.encodeRoot(rootManifest()).toByteArray()
        remote.files["vault/manga/manga-1.json"] = codec.encodeManga(mangaManifest()).toByteArray()
        val cache = FakeThumbnailCacheStore()
        val repository = repository()
        val service = DefaultVaultChapterThumbnailPublishService(
            networkHelper = mockk<NetworkHelper>(),
            json = json,
            repository = repository.repository,
            preferences = preferences(),
            refreshService = refresher(),
            cacheStore = cache,
            webDavFactory = { remote },
            identityFactory = identityFactory(
                "thumbnail-1",
                "stage-content",
                "manga-rev-2",
                "thumb-rev-1",
                "chapter-rev-2",
                "root-rev-2",
                "stage-manga",
                "stage-root",
            ),
            now = { 200L },
        )

        val result = service.publish(
            VaultChapterThumbnailPublishRequest(
                mangaId = 10,
                chapterId = 20,
                chapterIdentity = VaultIdentity("chapter-1"),
                sourcePageNumber = 3,
                jpegBytes = "thumbnail".toByteArray(),
            ),
        )

        result shouldBe VaultChapterThumbnailPublishResult.Published
        repository.chapters.single().thumbnail?.path shouldBe
            "content/manga-1/chapter-1/thumbnail/thumbnail-1.jpg"
        repository.jobs.values.single().let { job ->
            job.type shouldBe VaultTransferType.THUMBNAIL_PUBLISH
            job.state shouldBe VaultTransferState.SUCCEEDED
            job.sizeBytes shouldBe "thumbnail".toByteArray().size.toLong()
        }
        remote.directories shouldContainExactly listOf(
            "vault/content",
            "vault/content/manga-1",
            "vault/content/manga-1/chapter-1",
            "vault/content/manga-1/chapter-1/thumbnail",
        )
        remote.files.keys shouldContain "vault/content/manga-1/chapter-1/thumbnail/thumbnail-1.jpg"
        cache.writes.single().first.thumbnailIdentity shouldBe "thumbnail-1"
        cache.writes.single().second.decodeToString() shouldBe "thumbnail"
        remote.mangaManifest("vault/manga/manga-1.json")
            .chapters
            .single()
            .thumbnail
            ?.path shouldBe "content/manga-1/chapter-1/thumbnail/thumbnail-1.jpg"
        remote.rootManifest("vault/$ROOT_VAULT_MANIFEST_NAME").revisionNumber shouldBe 2
    }

    @Test
    fun `publish preserves known thumbnails when remote manga manifest is stale`() = runTest {
        val root = rootManifest(revision = VaultRevision("root-rev-2", 2))
        val remote = FakeWebDav()
        remote.files["vault/$ROOT_VAULT_MANIFEST_NAME"] = codec.encodeRoot(root).toByteArray()
        remote.files["vault/manga/manga-1.json"] = codec.encodeManga(
            mangaManifest(
                revision = VaultRevision("manga-rev-2", 2),
                chapters = listOf(manifestChapter(), manifestChapter(id = 21, identity = "chapter-2")),
            ),
        ).toByteArray()
        val repository = repository(
            vaultRevision = VaultRevision("root-rev-2", 2),
            chapters = listOf(
                chapter(thumbnail = thumbnail()),
                chapter(id = 21, identity = "chapter-2"),
            ),
        )
        val service = DefaultVaultChapterThumbnailPublishService(
            networkHelper = mockk<NetworkHelper>(),
            json = json,
            repository = repository.repository,
            preferences = preferences(),
            refreshService = refresher(),
            cacheStore = FakeThumbnailCacheStore(),
            webDavFactory = { remote },
            identityFactory = identityFactory(
                "thumbnail-2",
                "stage-content",
                "manga-rev-3",
                "thumb-rev-2",
                "chapter-rev-3",
                "root-rev-3",
                "stage-manga",
                "stage-root",
            ),
            now = { 300L },
        )

        val result = service.publish(
            VaultChapterThumbnailPublishRequest(
                mangaId = 10,
                chapterId = 21,
                chapterIdentity = VaultIdentity("chapter-2"),
                sourcePageNumber = 4,
                jpegBytes = "thumbnail-2".toByteArray(),
            ),
        )

        result shouldBe VaultChapterThumbnailPublishResult.Published
        remote.mangaManifest("vault/manga/manga-1.json")
            .chapters
            .map { it.identity to it.thumbnail?.path } shouldContainExactly listOf(
            "chapter-1" to "content/manga-1/chapter-1/thumbnail/thumb-1.jpg",
            "chapter-2" to "content/manga-1/chapter-2/thumbnail/thumbnail-2.jpg",
        )
    }

    @Test
    fun `publish verifies thumbnail content lands at manifest path after promote`() = runTest {
        val remote = FakeWebDav(promoteLeavesStagedFile = true)
        remote.files["vault/$ROOT_VAULT_MANIFEST_NAME"] = codec.encodeRoot(rootManifest()).toByteArray()
        remote.files["vault/manga/manga-1.json"] = codec.encodeManga(mangaManifest()).toByteArray()
        val service = DefaultVaultChapterThumbnailPublishService(
            networkHelper = mockk<NetworkHelper>(),
            json = json,
            repository = repository().repository,
            preferences = preferences(),
            refreshService = refresher(),
            cacheStore = FakeThumbnailCacheStore(),
            webDavFactory = { remote },
            identityFactory = identityFactory(
                "thumbnail-1",
                "stage-content",
                "manga-rev-2",
                "thumb-rev-1",
                "chapter-rev-2",
                "root-rev-2",
                "stage-manga",
                "stage-root",
            ),
            now = { 200L },
        )

        val result = service.publish(
            VaultChapterThumbnailPublishRequest(
                mangaId = 10,
                chapterId = 20,
                chapterIdentity = VaultIdentity("chapter-1"),
                sourcePageNumber = 3,
                jpegBytes = "thumbnail".toByteArray(),
            ),
        )

        result shouldBe VaultChapterThumbnailPublishResult.Published
        remote.files["vault/content/manga-1/chapter-1/thumbnail/thumbnail-1.jpg"]?.decodeToString() shouldBe
            "thumbnail"
        remote.files.keys shouldContain "vault/content/manga-1/chapter-1/thumbnail/thumbnail-1.jpg"
        remote.files.keys shouldContain "vault/manga/manga-1.json"
        remote.files.keys shouldContain "vault/$ROOT_VAULT_MANIFEST_NAME"
        remote.promotes.map { it.second } shouldContainExactly listOf(
            "vault/content/manga-1/chapter-1/thumbnail/thumbnail-1.jpg",
        )
    }

    @Test
    fun `display loader returns cached uri without remote fetch`() = runTest {
        val remote = FakeWebDav()
        val cache = FakeThumbnailCacheStore(
            localUris = mutableMapOf(cacheKey() to "file://cached-thumb.jpg"),
        )
        val loader = DefaultVaultChapterThumbnailDisplayLoader(
            networkHelper = mockk<NetworkHelper>(),
            repository = repository().repository,
            preferences = preferences(),
            cacheStore = cache,
            webDavFactory = { remote },
        )

        val result = loader.load(manga(), chapter(thumbnail = thumbnail()))

        result shouldBe VaultChapterThumbnailDisplayResult.Ready("file://cached-thumb.jpg")
        remote.getBytesPaths shouldBe emptyList()
    }

    @Test
    fun `display loader local lookup does not download missing thumbnail`() = runTest {
        val bytes = "thumbnail".toByteArray()
        val remote = FakeWebDav()
        remote.files["vault/content/manga-1/chapter-1/thumbnail/thumb-1.jpg"] = bytes
        val cache = FakeThumbnailCacheStore()
        val loader = DefaultVaultChapterThumbnailDisplayLoader(
            networkHelper = mockk<NetworkHelper>(),
            repository = repository().repository,
            preferences = preferences(),
            cacheStore = cache,
            webDavFactory = { remote },
        )

        val result = loader.loadLocal(manga(), chapter(thumbnail = thumbnail(bytes)))

        result shouldBe VaultChapterThumbnailDisplayResult.Unavailable
        remote.getBytesPaths shouldBe emptyList()
        cache.writes shouldBe emptyList()
    }

    @Test
    fun `display loader downloads verifies caches and returns local uri`() = runTest {
        val bytes = "thumbnail".toByteArray()
        val remote = FakeWebDav()
        remote.files["vault/content/manga-1/chapter-1/thumbnail/thumb-1.jpg"] = bytes
        val cache = FakeThumbnailCacheStore()
        val loader = DefaultVaultChapterThumbnailDisplayLoader(
            networkHelper = mockk<NetworkHelper>(),
            repository = repository().repository,
            preferences = preferences(),
            cacheStore = cache,
            webDavFactory = { remote },
        )

        val result = loader.load(manga(), chapter(thumbnail = thumbnail(bytes)))

        result shouldBe VaultChapterThumbnailDisplayResult.Ready("cache://thumb-1")
        remote.getBytesPaths shouldContainExactly listOf("vault/content/manga-1/chapter-1/thumbnail/thumb-1.jpg")
        cache.writes.single().second shouldBe bytes
    }

    private fun preferences(): ContentVaultPreferences {
        val configuredIdentity = mockk<Preference<String>> {
            every { get() } returns "vault-1"
        }
        return mockk {
            every { getWebDavConfig() } returns config
            every { this@mockk.configuredVaultIdentity } returns configuredIdentity
        }
    }

    private fun refresher(): VaultCatalogueRefresher {
        return object : VaultCatalogueRefresher {
            override suspend fun refreshConfiguredVault(): VaultCatalogueRefreshResult {
                return VaultCatalogueRefreshResult.Refreshed(ContentVaultIdentity("vault-1"), 1, 1)
            }
        }
    }

    private fun repository(
        vaultRevision: VaultRevision = VaultRevision("root-rev-1", 1),
        chapters: List<VaultChapter> = listOf(chapter()),
    ): RepositoryFixture {
        val vault = ContentVault(
            id = 1,
            identity = ContentVaultIdentity("vault-1"),
            displayName = "Vault",
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            rootRevision = vaultRevision,
            writerId = null,
            lastCatalogueRefreshAt = null,
            createdAt = 100L,
            updatedAt = 100L,
        )
        val manga = manga()
        val mutableChapters = chapters.toMutableList()
        val jobs = mutableMapOf<Long, VaultTransferJob>()
        var nextJobId = 1L
        val repository = mockk<VaultRepository> {
            coEvery { getVaultByIdentity(ContentVaultIdentity("vault-1")) } returns vault
            coEvery { getMangaById(10) } returns manga
            coEvery { getChapters(10) } coAnswers { mutableChapters.toList() }
            coEvery { upsertChapter(10, any()) } coAnswers {
                val updatedChapter = invocation.args[1] as VaultChapter
                mutableChapters.replaceAll {
                    if (it.identity == updatedChapter.identity) {
                        updatedChapter
                    } else {
                        it
                    }
                }
                updatedChapter.id
            }
            coEvery { getTransferJobsForVault(1) } coAnswers { jobs.values.toList() }
            coEvery { getTransferJob(any()) } coAnswers { jobs[invocation.args[0] as Long] }
            coEvery { upsertTransferJob(any()) } coAnswers {
                val job = invocation.args[0] as VaultTransferJob
                val id = job.id.takeIf { it > 0 } ?: nextJobId++
                jobs[id] = job.copy(id = id)
                id
            }
        }
        return RepositoryFixture(repository, jobs, mutableChapters)
    }

    private fun manga() = VaultManga(
        id = 10,
        vaultId = 1,
        identity = VaultIdentity("manga-1"),
        metadata = VaultMetadata("Manga", null, null, null, VaultMangaStatus.UNKNOWN),
        sortKey = "manga",
        coverId = null,
        revision = VaultRevision("manga-rev-1", 1),
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun chapter(
        id: Long = 20,
        identity: String = "chapter-1",
        thumbnail: VaultChapterThumbnail? = null,
    ) = VaultChapter(
        id = id,
        mangaId = 10,
        identity = VaultIdentity(identity),
        title = "Chapter 1",
        chapterNumber = 1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 1,
        content = VaultChapterContent("content/manga-1/$identity/chapter.cbz", VaultChapterContentFormat.CBZ, 1, "sha"),
        revision = VaultRevision("chapter-rev-1", 1),
        dateUpload = 100L,
        createdAt = 100L,
        updatedAt = 100L,
        thumbnail = thumbnail,
    )

    private fun thumbnail(bytes: ByteArray = "thumbnail".toByteArray()) = VaultChapterThumbnail(
        id = 30,
        chapterId = 20,
        identity = VaultIdentity("thumb-1"),
        path = "content/manga-1/chapter-1/thumbnail/thumb-1.jpg",
        mediaType = "image/jpeg",
        sizeBytes = bytes.size.toLong(),
        checksumSha256 = bytes.vaultTransferIntegrity().checksumSha256,
        revision = VaultRevision("thumb-rev-1", 1),
        updatedAt = 100L,
    )

    private fun rootManifest(
        revision: VaultRevision = VaultRevision("root-rev-1", 1),
    ) = VaultRootManifest(
        identity = "vault-1",
        displayName = "Vault",
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        revisionId = revision.id,
        revisionNumber = revision.number,
        writerId = null,
        createdAt = 100L,
        updatedAt = 100L,
        summary = VaultCatalogueSummary(mangaCount = 1, chapterCount = 1, updatedAt = 100L),
        manga = listOf(
            VaultMangaManifestPointer(
                identity = "manga-1",
                path = "manga/manga-1.json",
                title = "Manga",
                revisionId = "manga-rev-1",
                revisionNumber = 1,
                updatedAt = 100L,
            ),
        ),
    )

    private fun mangaManifest(
        revision: VaultRevision = VaultRevision("manga-rev-1", 1),
        chapters: List<VaultManifestChapter> = listOf(manifestChapter()),
    ) = VaultMangaManifest(
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        vaultIdentity = "vault-1",
        mangaIdentity = "manga-1",
        revisionId = revision.id,
        revisionNumber = revision.number,
        metadata = VaultManifestMetadata(title = "Manga"),
        chapters = chapters,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun manifestChapter(
        id: Long = 20,
        identity: String = "chapter-1",
        thumbnail: tachiyomi.domain.vault.model.VaultManifestChapterThumbnail? = null,
    ) = VaultManifestChapter(
        identity = identity,
        title = "Chapter ${id - 19}",
        chapterNumber = (id - 19).toDouble(),
        sourceOrder = (id - 19),
        content = VaultManifestChapterContent(
            path = "content/manga-1/$identity/chapter.cbz",
            format = VaultChapterContentFormat.CBZ,
            integrity = VaultContentIntegrity(1, "sha"),
        ),
        thumbnail = thumbnail,
        revisionId = "chapter-rev-1",
        revisionNumber = 1,
        dateUpload = 100L,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun cacheKey() = VaultChapterThumbnailCacheKey(
        vaultId = 1,
        mangaIdentity = "manga-1",
        chapterIdentity = "chapter-1",
        thumbnailIdentity = "thumb-1",
        remotePath = "content/manga-1/chapter-1/thumbnail/thumb-1.jpg",
    )

    private fun identityFactory(vararg values: String): () -> String {
        val identities = ArrayDeque(values.toList())
        return { identities.removeFirst() }
    }

    private inner class FakeWebDav(
        private val promoteLeavesStagedFile: Boolean = false,
    ) : VaultWebDav {
        val files = mutableMapOf<String, ByteArray>()
        val directories = mutableListOf<String>()
        val getBytesPaths = mutableListOf<String>()
        val promotes = mutableListOf<Pair<String, String>>()

        override suspend fun get(path: String): String? = files[path]?.decodeToString()

        override suspend fun getBytes(path: String): ByteArray? {
            getBytesPaths += path
            return files[path]
        }

        override suspend fun put(path: String, body: String): Boolean {
            files[path] = body.toByteArray()
            return true
        }

        override suspend fun putFile(path: String, file: UniFile): Boolean = false

        override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean {
            files[path] = bytes
            return true
        }

        override suspend fun createDirectory(path: String): Boolean {
            directories += path
            return true
        }

        override suspend fun delete(path: String): Boolean {
            files.remove(path)
            return true
        }

        override suspend fun promote(stagedPath: String, finalPath: String): Boolean {
            promotes += stagedPath to finalPath
            if (promoteLeavesStagedFile) {
                return files[stagedPath] != null
            }
            files[finalPath] = files.remove(stagedPath) ?: return false
            return true
        }

        fun rootManifest(path: String) = codec.decodeRoot(files[path]?.decodeToString() ?: error("missing root"))
            .let { (it as tachiyomi.domain.vault.model.VaultManifestReadResult.Success).manifest }

        fun mangaManifest(path: String) = codec.decodeManga(files[path]?.decodeToString() ?: error("missing manga"))
            .let { (it as tachiyomi.domain.vault.model.VaultManifestReadResult.Success).manifest }
    }

    private class FakeThumbnailCacheStore(
        private val localUris: MutableMap<VaultChapterThumbnailCacheKey, String> = mutableMapOf(),
    ) : VaultChapterThumbnailCacheStore {
        val writes = mutableListOf<Pair<VaultChapterThumbnailCacheKey, ByteArray>>()

        override fun localUri(key: VaultChapterThumbnailCacheKey): String? = localUris[key]

        override suspend fun write(key: VaultChapterThumbnailCacheKey, bytes: ByteArray) {
            writes += key to bytes
            localUris[key] = "cache://${key.thumbnailIdentity}"
        }

        override suspend fun delete(key: VaultChapterThumbnailCacheKey) {
            localUris.remove(key)
        }
    }

    private data class RepositoryFixture(
        val repository: VaultRepository,
        val jobs: Map<Long, VaultTransferJob>,
        val chapters: List<VaultChapter>,
    )
}
