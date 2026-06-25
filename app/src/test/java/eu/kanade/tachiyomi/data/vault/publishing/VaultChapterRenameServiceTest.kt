package eu.kanade.tachiyomi.data.vault.publishing

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteListResult
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteReadResult
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorage
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteWriteResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
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
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

class VaultChapterRenameServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val codec = VaultManifestCodec(json)
    private val config = WebDavVaultConfig(
        serverUrl = "https://example.invalid",
        username = "user",
        password = "pass",
        rootPath = "vault",
    )

    @Test
    fun `successful rename updates only chapter title and manifest revisions`() = runTest {
        val remoteStorage = FakeVaultRemoteStorage(
            files = mutableMapOf(
                "vault/$ROOT_VAULT_MANIFEST_NAME" to codec.encodeRoot(rootManifest()),
                "vault/manga/manga-1.json" to codec.encodeManga(
                    mangaManifest(chapters = listOf(chapter("chapter-1", title = "Old title"))),
                ),
            ),
        )
        val service = service(remoteStorage)

        val result = service.rename(
            mangaId = 10,
            chapterId = 20,
            chapterIdentity = "chapter-1",
            title = "  New title  ",
            ignoredJobId = 99,
        )

        result shouldBe VaultChapterRenameResult.Renamed
        val updatedRoot = codec.decodeRootSuccess(remoteStorage.files.getValue("vault/$ROOT_VAULT_MANIFEST_NAME"))
        val updatedManga = codec.decodeMangaSuccess(remoteStorage.files.getValue("vault/manga/manga-1.json"))
        val updatedChapter = updatedManga.chapters.single()
        updatedChapter.title shouldBe "New title"
        updatedChapter.identity shouldBe "chapter-1"
        updatedChapter.content.path shouldBe "content/manga-1/chapter-1/chapter.cbz"
        updatedChapter.revisionNumber shouldBe 2
        updatedManga.revisionNumber shouldBe 2
        updatedRoot.revisionNumber shouldBe 3
        updatedRoot.summary.chapterCount shouldBe 1
    }

    @Test
    fun `blank title is rejected before remote reads`() = runTest {
        val remoteStorage = FakeVaultRemoteStorage(mutableMapOf())
        val service = service(remoteStorage)

        val result = service.rename(
            mangaId = 10,
            chapterId = 20,
            chapterIdentity = "chapter-1",
            title = "   ",
            ignoredJobId = 99,
        )

        result shouldBe VaultChapterRenameResult.TitleRequired
        remoteStorage.getTextCount shouldBe 0
    }

    @Test
    fun `local chapter identity mismatch is rejected`() = runTest {
        val remoteStorage = FakeVaultRemoteStorage(mutableMapOf())
        val service = service(remoteStorage, chapter = localChapter(identity = "changed-chapter"))

        val result = service.rename(
            mangaId = 10,
            chapterId = 20,
            chapterIdentity = "chapter-1",
            title = "New title",
            ignoredJobId = 99,
        )

        result shouldBe VaultChapterRenameResult.ChapterIdentityMismatch
        remoteStorage.getTextCount shouldBe 0
    }

    private fun service(
        remoteStorage: FakeVaultRemoteStorage,
        chapter: VaultChapter = localChapter(),
    ): VaultChapterRenameService {
        val preferences = ContentVaultPreferences(InMemoryPreferenceStore()).also {
            it.setWebDavConfig(config, ContentVaultIdentity("vault-1"))
        }
        val repository = mockk<VaultRepository> {
            coEvery { getMangaById(10) } returns manga()
            coEvery { getChapters(10) } returns listOf(chapter)
            coEvery { getTransferJobsForVault(1) } returns emptyList()
            coEvery { getVaultByIdentity(ContentVaultIdentity("vault-1")) } returns contentVault()
        }
        return VaultChapterRenameService(
            json = json,
            repository = repository,
            preferences = preferences,
            remoteStorageFactory = FakeVaultRemoteStorageFactory(remoteStorage),
            now = { 100 },
        )
    }

    private fun contentVault() = ContentVault(
        id = 1,
        identity = ContentVaultIdentity("vault-1"),
        displayName = "Vault",
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        rootRevision = VaultRevision(id = "root-rev", number = 2),
        writerId = null,
        lastCatalogueRefreshAt = null,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun manga() = VaultManga(
        id = 10,
        vaultId = 1,
        identity = VaultIdentity("manga-1"),
        metadata = VaultMetadata(
            title = "Manga",
            author = null,
            artist = null,
            description = null,
            status = VaultMangaStatus.UNKNOWN,
        ),
        sortKey = "manga",
        coverId = null,
        revision = VaultRevision(id = "manga-rev", number = 1),
        createdAt = 1,
        updatedAt = 1,
    )

    private fun localChapter(identity: String = "chapter-1") = VaultChapter(
        id = 20,
        mangaId = 10,
        identity = VaultIdentity(identity),
        title = "Old title",
        chapterNumber = 1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 1,
        content = VaultChapterContent(
            path = "content/manga-1/chapter-1/chapter.cbz",
            format = VaultChapterContentFormat.CBZ,
            sizeBytes = 1,
            checksumSha256 = "abc",
        ),
        revision = VaultRevision(id = "chapter-rev", number = 1),
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun rootManifest() = VaultRootManifest(
        identity = "vault-1",
        displayName = "Vault",
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        revisionId = "root-rev",
        revisionNumber = 2,
        writerId = null,
        createdAt = 1,
        updatedAt = 2,
        summary = VaultCatalogueSummary(mangaCount = 1, chapterCount = 1, labelCount = 0, updatedAt = 2),
        manga = listOf(
            VaultMangaManifestPointer(
                identity = "manga-1",
                path = "manga/manga-1.json",
                title = "Manga",
                revisionId = "manga-rev",
                revisionNumber = 1,
                updatedAt = 2,
            ),
        ),
    )

    private fun mangaManifest(chapters: List<VaultManifestChapter>) = VaultMangaManifest(
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        vaultIdentity = "vault-1",
        mangaIdentity = "manga-1",
        revisionId = "manga-rev",
        revisionNumber = 1,
        metadata = VaultManifestMetadata(title = "Manga"),
        chapters = chapters,
        createdAt = 1,
        updatedAt = 2,
    )

    private fun chapter(identity: String, title: String) = VaultManifestChapter(
        identity = identity,
        title = title,
        chapterNumber = 1.0,
        sourceOrder = 1,
        content = VaultManifestChapterContent(
            path = "content/manga-1/$identity/chapter.cbz",
            format = VaultChapterContentFormat.CBZ,
            integrity = VaultContentIntegrity(sizeBytes = 1, checksumSha256 = "abc"),
        ),
        revisionId = "chapter-rev",
        revisionNumber = 1,
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun VaultManifestCodec.decodeRootSuccess(body: String) =
        (decodeRoot(body) as tachiyomi.domain.vault.model.VaultManifestReadResult.Success).manifest

    private fun VaultManifestCodec.decodeMangaSuccess(body: String) =
        (decodeManga(body) as tachiyomi.domain.vault.model.VaultManifestReadResult.Success).manifest

    private class FakeVaultRemoteStorageFactory(
        private val storage: VaultRemoteStorage,
    ) : VaultRemoteStorageFactory {
        override fun create(config: WebDavVaultConfig): VaultRemoteStorage = storage
    }

    private class FakeVaultRemoteStorage(
        val files: MutableMap<String, String>,
    ) : VaultRemoteStorage {
        var getTextCount = 0

        override suspend fun getText(path: String): VaultRemoteReadResult<String> {
            getTextCount += 1
            return files[path]?.let { VaultRemoteReadResult.Found(it) } ?: VaultRemoteReadResult.NotFound
        }

        override suspend fun putText(path: String, body: String): VaultRemoteWriteResult {
            files[path] = body
            return VaultRemoteWriteResult.Success
        }

        override suspend fun move(stagedPath: String, finalPath: String): VaultRemoteWriteResult {
            files[finalPath] = files.remove(stagedPath) ?: return VaultRemoteWriteResult.Failed(null)
            return VaultRemoteWriteResult.Success
        }

        override suspend fun delete(path: String): VaultRemoteWriteResult {
            files.remove(path)
            return VaultRemoteWriteResult.Success
        }

        override suspend fun list(path: String): VaultRemoteListResult = error("Unsupported")
        override suspend fun getBytes(path: String): VaultRemoteReadResult<ByteArray> = error("Unsupported")
        override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): VaultRemoteWriteResult =
            error("Unsupported")

        override suspend fun putFile(path: String, file: UniFile): VaultRemoteWriteResult = error("Unsupported")
        override suspend fun createDirectory(path: String): VaultRemoteWriteResult = error("Unsupported")
    }
}
