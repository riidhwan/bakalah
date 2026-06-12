package eu.kanade.tachiyomi.data.vault.publishing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultMangaManifestPointer
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRootManifest
import tachiyomi.domain.vault.model.WebDavVaultConfig

class VaultManifestPublishTransactionTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val codec = VaultManifestCodec(json)
    private val transaction = VaultManifestPublishTransaction(json)
    private val config = WebDavVaultConfig(
        serverUrl = "https://example.invalid",
        username = "user",
        password = "password",
        rootPath = "vault",
    )

    @Test
    fun `missing root manifest uses caller manifest failure`() = runTest {
        val storage = FakeStorage()

        val error = shouldThrow<TestGlobalFailure> {
            transaction.prepare(
                storage = storage,
                config = config,
                target = VaultManifestPublishTarget.CreateNew("manga-1", "manga/new.json"),
                expectedVaultIdentity = "vault-1",
                globalFailure = ::TestGlobalFailure,
            )
        }

        error.category shouldBe "manifest"
    }

    @Test
    fun `identity mismatch uses caller identity failure`() = runTest {
        val storage = FakeStorage()
        storage.files[rootPath()] = codec.encodeRoot(rootManifest(identity = "other-vault"))

        val error = shouldThrow<TestGlobalFailure> {
            transaction.prepare(
                storage = storage,
                config = config,
                target = VaultManifestPublishTarget.CreateNew("manga-1", "manga/new.json"),
                expectedVaultIdentity = "vault-1",
                globalFailure = ::TestGlobalFailure,
            )
        }

        error.category shouldBe "identity"
    }

    @Test
    fun `missing existing target pointer uses caller target failure`() = runTest {
        val storage = FakeStorage()
        storage.files[rootPath()] = codec.encodeRoot(rootManifest())

        val error = shouldThrow<TestGlobalFailure> {
            transaction.prepare(
                storage = storage,
                config = config,
                target = VaultManifestPublishTarget.Existing("missing"),
                expectedVaultIdentity = "vault-1",
                globalFailure = ::TestGlobalFailure,
            )
        }

        error.category shouldBe "target"
    }

    @Test
    fun `missing existing target manifest uses caller target failure`() = runTest {
        val storage = FakeStorage()
        storage.files[rootPath()] = codec.encodeRoot(
            rootManifest(
                manga = listOf(pointer(identity = "manga-1", path = "manga/one.json")),
                chapterCount = 1,
            ),
        )

        val error = shouldThrow<TestGlobalFailure> {
            transaction.prepare(
                storage = storage,
                config = config,
                target = VaultManifestPublishTarget.Existing("manga-1"),
                expectedVaultIdentity = "vault-1",
                globalFailure = ::TestGlobalFailure,
            )
        }

        error.category shouldBe "target"
    }

    @Test
    fun `commit writes manga manifest and updates root pointer summary`() = runTest {
        val storage = FakeStorage()
        storage.files[rootPath()] = codec.encodeRoot(rootManifest())
        val context = transaction.prepare(
            storage = storage,
            config = config,
            target = VaultManifestPublishTarget.CreateNew("manga-1", "manga/new.json"),
            expectedVaultIdentity = "vault-1",
            globalFailure = ::TestGlobalFailure,
        )

        transaction.commit(
            storage = storage,
            config = config,
            context = context,
            metadata = metadata("Manga One"),
            mangaManifest = mangaManifest(chapters = listOf(chapter("chapter-1"), chapter("chapter-2"))),
            mangaRevisionId = "manga-rev-1",
            mangaRevisionNumber = 1,
            now = 200L,
            newUploads = listOf(newUpload("content/manga-1/chapter-1/chapter.cbz")),
            globalFailure = ::TestGlobalFailure,
        )

        storage.files.keys shouldContain "vault/manga/new.json"
        val root = storage.root()
        root.revisionNumber shouldBe 2
        root.summary.mangaCount shouldBe 1
        root.summary.chapterCount shouldBe 2
        root.manga.single().identity shouldBe "manga-1"
        root.manga.single().title shouldBe "Manga One"
        root.manga.single().revisionId shouldBe "manga-rev-1"
        root.manga.single().revisionNumber shouldBe 1
    }

    @Test
    fun `root publish failure restores existing manga manifest and deletes new content and cover`() = runTest {
        val oldManifest = mangaManifest(
            revisionId = "old-rev",
            revisionNumber = 1,
            chapters = listOf(chapter("old-chapter")),
        )
        val storage = FakeStorage(failRootPut = true)
        storage.files[rootPath()] = codec.encodeRoot(
            rootManifest(
                manga = listOf(pointer(identity = "manga-1", path = "manga/one.json", revisionNumber = 1)),
                chapterCount = 1,
            ),
        )
        storage.files["vault/manga/one.json"] = codec.encodeManga(oldManifest)
        val context = transaction.prepare(
            storage = storage,
            config = config,
            target = VaultManifestPublishTarget.Existing("manga-1"),
            expectedVaultIdentity = "vault-1",
            globalFailure = ::TestGlobalFailure,
        )

        val error = shouldThrow<TestGlobalFailure> {
            transaction.commit(
                storage = storage,
                config = config,
                context = context,
                metadata = metadata("Manga One"),
                mangaManifest = mangaManifest(
                    revisionId = "new-rev",
                    revisionNumber = 2,
                    chapters = listOf(chapter("old-chapter"), chapter("new-chapter")),
                ),
                mangaRevisionId = "new-rev",
                mangaRevisionNumber = 2,
                now = 200L,
                newUploads = listOf(
                    newUpload("content/manga-1/new-chapter/new.cbz"),
                    newUpload("content/manga-1/cover/new.jpg"),
                ),
                globalFailure = ::TestGlobalFailure,
            )
        }

        error.category shouldBe "publish"
        storage.manga("manga/one.json").revisionId shouldBe "old-rev"
        storage.deletes shouldContain "vault/content/manga-1/new-chapter/new.cbz"
        storage.deletes shouldContain "vault/content/manga-1/cover/new.jpg"
    }

    @Test
    fun `root publish failure for create-new deletes new manga manifest and content`() = runTest {
        val storage = FakeStorage(failRootPut = true)
        storage.files[rootPath()] = codec.encodeRoot(rootManifest())
        val context = transaction.prepare(
            storage = storage,
            config = config,
            target = VaultManifestPublishTarget.CreateNew("manga-1", "manga/new.json"),
            expectedVaultIdentity = "vault-1",
            globalFailure = ::TestGlobalFailure,
        )

        val error = shouldThrow<TestGlobalFailure> {
            transaction.commit(
                storage = storage,
                config = config,
                context = context,
                metadata = metadata("Manga One"),
                mangaManifest = mangaManifest(chapters = listOf(chapter("chapter-1"))),
                mangaRevisionId = "manga-rev-1",
                mangaRevisionNumber = 1,
                now = 200L,
                newUploads = listOf(newUpload("content/manga-1/chapter-1/chapter.cbz")),
                globalFailure = ::TestGlobalFailure,
            )
        }

        error.category shouldBe "publish"
        storage.deletes shouldContain "vault/manga/new.json"
        storage.deletes shouldContain "vault/content/manga-1/chapter-1/chapter.cbz"
    }

    @Test
    fun `manga manifest publish failure deletes new content and cover`() = runTest {
        val storage = FakeStorage(failMangaPut = true)
        storage.files[rootPath()] = codec.encodeRoot(rootManifest())
        val context = transaction.prepare(
            storage = storage,
            config = config,
            target = VaultManifestPublishTarget.CreateNew("manga-1", "manga/new.json"),
            expectedVaultIdentity = "vault-1",
            globalFailure = ::TestGlobalFailure,
        )

        val error = shouldThrow<IllegalStateException> {
            transaction.commit(
                storage = storage,
                config = config,
                context = context,
                metadata = metadata("Manga One"),
                mangaManifest = mangaManifest(chapters = listOf(chapter("chapter-1"))),
                mangaRevisionId = "manga-rev-1",
                mangaRevisionNumber = 1,
                now = 200L,
                newUploads = listOf(
                    newUpload("content/manga-1/chapter-1/chapter.cbz"),
                    newUpload("content/manga-1/cover/new.jpg"),
                ),
                globalFailure = ::TestGlobalFailure,
            )
        }

        error.message shouldBe "publish"
        storage.deletes shouldContain "vault/.staging/add-to-vault/chapter-1/chapter.cbz"
        storage.deletes shouldContain "vault/content/manga-1/chapter-1/chapter.cbz"
        storage.deletes shouldContain "vault/.staging/add-to-vault/cover/new.jpg"
        storage.deletes shouldContain "vault/content/manga-1/cover/new.jpg"
    }

    @Test
    fun `promotion failure fails before manifest mutation and deletes staged and final content`() = runTest {
        val storage = FakeStorage(failPromote = true)
        storage.files[rootPath()] = codec.encodeRoot(rootManifest())
        val context = transaction.prepare(
            storage = storage,
            config = config,
            target = VaultManifestPublishTarget.CreateNew("manga-1", "manga/new.json"),
            expectedVaultIdentity = "vault-1",
            globalFailure = ::TestGlobalFailure,
        )

        val error = shouldThrow<TestGlobalFailure> {
            transaction.commit(
                storage = storage,
                config = config,
                context = context,
                metadata = metadata("Manga One"),
                mangaManifest = mangaManifest(chapters = listOf(chapter("chapter-1"))),
                mangaRevisionId = "manga-rev-1",
                mangaRevisionNumber = 1,
                now = 200L,
                newUploads = listOf(newUpload("content/manga-1/chapter-1/chapter.cbz")),
                globalFailure = ::TestGlobalFailure,
            )
        }

        error.category shouldBe "promote"
        storage.files.keys shouldContainExactly listOf(rootPath())
        storage.deletes shouldContain "vault/.staging/add-to-vault/chapter-1/chapter.cbz"
        storage.deletes shouldContain "vault/content/manga-1/chapter-1/chapter.cbz"
    }

    @Test
    fun `optional upload promotion returns final path when promoted`() = runTest {
        val storage = FakeStorage()
        val upload = newUpload("content/manga-1/cover/cover.jpg")
        storage.files["vault/${upload.stagedPath}"] = "cover"

        val promotedPath = transaction.promoteOptionalUpload(storage, config, upload)

        promotedPath shouldBe upload.finalPath
        storage.files["vault/${upload.finalPath}"] shouldBe "cover"
    }

    @Test
    fun `optional upload promotion failure deletes staged and final content without throwing`() = runTest {
        val storage = FakeStorage(failPromote = true)
        val upload = newUpload("content/manga-1/cover/cover.jpg")
        storage.files["vault/${upload.stagedPath}"] = "cover"

        val promotedPath = transaction.promoteOptionalUpload(storage, config, upload)

        promotedPath shouldBe null
        storage.deletes shouldContain "vault/${upload.stagedPath}"
        storage.deletes shouldContain "vault/${upload.finalPath}"
    }

    private fun rootPath() = "vault/$ROOT_VAULT_MANIFEST_NAME"

    private fun rootManifest(
        identity: String = "vault-1",
        manga: List<VaultMangaManifestPointer> = emptyList(),
        chapterCount: Long = 0,
    ) = VaultRootManifest(
        identity = identity,
        displayName = "Vault",
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        revisionId = "root-rev-1",
        revisionNumber = 1,
        writerId = null,
        createdAt = 100L,
        updatedAt = 100L,
        summary = VaultCatalogueSummary(
            mangaCount = manga.size.toLong(),
            chapterCount = chapterCount,
            labelCount = 0,
            updatedAt = 100L,
        ),
        manga = manga,
    )

    private fun pointer(
        identity: String,
        path: String,
        title: String = "Manga One",
        revisionId: String = "manga-rev-1",
        revisionNumber: Long = 1,
    ) = VaultMangaManifestPointer(
        identity = identity,
        path = path,
        title = title,
        revisionId = revisionId,
        revisionNumber = revisionNumber,
        updatedAt = 100L,
    )

    private fun mangaManifest(
        revisionId: String = "manga-rev-1",
        revisionNumber: Long = 1,
        chapters: List<VaultManifestChapter> = emptyList(),
    ) = VaultMangaManifest(
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        vaultIdentity = "vault-1",
        mangaIdentity = "manga-1",
        revisionId = revisionId,
        revisionNumber = revisionNumber,
        metadata = VaultManifestMetadata(title = "Manga One"),
        chapters = chapters,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun chapter(identity: String) = VaultManifestChapter(
        identity = identity,
        title = identity,
        chapterNumber = 1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 0,
        content = VaultManifestChapterContent(
            path = "content/manga-1/$identity/$identity.cbz",
            format = VaultChapterContentFormat.CBZ,
            integrity = VaultContentIntegrity(10L, "checksum"),
        ),
        revisionId = "$identity-rev",
        revisionNumber = 1,
        dateUpload = 0L,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun metadata(title: String) = VaultMetadata(
        title = title,
        author = null,
        artist = null,
        description = null,
        status = VaultMangaStatus.UNKNOWN,
    )

    private fun newUpload(finalPath: String): VaultPromotableUpload {
        val contentIdentity = finalPath.substringAfter("content/manga-1/").substringBefore('/')
        val fileName = finalPath.substringAfterLast('/')
        return VaultPromotableUpload(
            stagedPath = ".staging/add-to-vault/$contentIdentity/$fileName",
            finalPath = finalPath,
        )
    }

    private inner class FakeStorage(
        private val failRootPut: Boolean = false,
        private val failMangaPut: Boolean = false,
        private val failPromote: Boolean = false,
    ) : VaultManifestPublishStorage {
        val files = linkedMapOf<String, String>()
        val deletes = mutableListOf<String>()

        override suspend fun get(path: String): String? = files[path]

        override suspend fun put(path: String, body: String): Boolean {
            if (path == rootPath() && failRootPut) return false
            if (path.startsWith("vault/manga/") && failMangaPut) return false
            files[path] = body
            return true
        }

        override suspend fun delete(path: String): Boolean {
            deletes += path
            files.remove(path)
            return true
        }

        override suspend fun createDirectory(path: String): Boolean = true

        override suspend fun promote(stagedPath: String, finalPath: String): Boolean {
            if (failPromote) return false
            files[finalPath] = files.remove(stagedPath).orEmpty()
            return true
        }

        fun root(): VaultRootManifest = codec.decodeRoot(files.getValue(rootPath())).let { result ->
            (result as tachiyomi.domain.vault.model.VaultManifestReadResult.Success).manifest
        }

        fun manga(path: String): VaultMangaManifest =
            codec.decodeManga(files.getValue("vault/$path")).let { result ->
                (result as tachiyomi.domain.vault.model.VaultManifestReadResult.Success).manifest
            }
    }

    private class TestGlobalFailure(
        val category: String,
    ) : RuntimeException(category)
}
