package eu.kanade.tachiyomi.data.vault.publishing

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.reader.ActiveVaultReaderSessions
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteListResult
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteReadResult
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorage
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteWriteResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
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

class VaultChapterDeletionServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val codec = VaultManifestCodec(json)

    @Test
    fun `already absent remote chapter succeeds when local chapter row is gone`() = runTest {
        val config = WebDavVaultConfig(
            serverUrl = "https://example.invalid",
            username = "user",
            password = "pass",
            rootPath = "vault",
        )
        val preferences = ContentVaultPreferences(InMemoryPreferenceStore()).also {
            it.setWebDavConfig(config, ContentVaultIdentity("vault-1"))
        }
        val repository = mockk<VaultRepository> {
            coEvery { getMangaById(10) } returns manga()
            coEvery { getChapters(10) } returns emptyList()
            coEvery { getTransferJobsForVault(1) } returns emptyList()
            coEvery { getVaultByIdentity(ContentVaultIdentity("vault-1")) } returns contentVault()
        }
        val storageManager = mockk<StorageManager> {
            every { getVaultCacheDirectory() } returns null
        }
        val remoteStorage = FakeVaultRemoteStorage(
            files = mapOf(
                "vault/$ROOT_VAULT_MANIFEST_NAME" to codec.encodeRoot(rootManifest()),
                "vault/manga/manga-1.json" to codec.encodeManga(
                    mangaManifest(chapters = listOf(chapter("chapter-2"))),
                ),
            ),
        )
        val service = VaultChapterDeletionService(
            json = json,
            repository = repository,
            preferences = preferences,
            activeReaderSessions = ActiveVaultReaderSessions(),
            storageManager = storageManager,
            remoteStorageFactory = FakeVaultRemoteStorageFactory(remoteStorage),
        )

        val result = service.delete(
            mangaId = 10,
            chapterId = 20,
            chapterIdentity = "chapter-1",
            ignoredJobId = 99,
        )

        result shouldBe VaultChapterDeletionResult.Deleted
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

    private fun chapter(identity: String) = VaultManifestChapter(
        identity = identity,
        title = identity,
        chapterNumber = 1.0,
        sourceOrder = 1,
        content = VaultManifestChapterContent(
            path = "content/manga-1/$identity/chapter.cbz",
            format = tachiyomi.domain.vault.model.VaultChapterContentFormat.CBZ,
            integrity = VaultContentIntegrity(sizeBytes = 1, checksumSha256 = "abc"),
        ),
        revisionId = "$identity-rev",
        revisionNumber = 1,
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private class FakeVaultRemoteStorageFactory(
        private val storage: VaultRemoteStorage,
    ) : VaultRemoteStorageFactory {
        override fun create(config: WebDavVaultConfig): VaultRemoteStorage = storage
    }

    private class FakeVaultRemoteStorage(
        private val files: Map<String, String>,
    ) : VaultRemoteStorage {
        override suspend fun getText(path: String): VaultRemoteReadResult<String> {
            return files[path]?.let { VaultRemoteReadResult.Found(it) } ?: VaultRemoteReadResult.NotFound
        }

        override suspend fun list(path: String): VaultRemoteListResult = error("Unsupported")
        override suspend fun getBytes(path: String): VaultRemoteReadResult<ByteArray> = error("Unsupported")
        override suspend fun putText(path: String, body: String): VaultRemoteWriteResult = error("Unsupported")
        override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): VaultRemoteWriteResult =
            error("Unsupported")

        override suspend fun putFile(path: String, file: UniFile): VaultRemoteWriteResult = error("Unsupported")
        override suspend fun createDirectory(path: String): VaultRemoteWriteResult = error("Unsupported")
        override suspend fun delete(path: String): VaultRemoteWriteResult = error("Unsupported")
        override suspend fun move(stagedPath: String, finalPath: String): VaultRemoteWriteResult = error("Unsupported")
    }
}
