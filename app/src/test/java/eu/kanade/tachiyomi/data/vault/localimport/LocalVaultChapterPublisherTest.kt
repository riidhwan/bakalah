package eu.kanade.tachiyomi.data.vault.localimport

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationQueueDrainer
import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishGate
import eu.kanade.tachiyomi.data.vault.remote.childPath
import eu.kanade.tachiyomi.data.vault.staging.CbzEntry
import eu.kanade.tachiyomi.data.vault.staging.digest
import eu.kanade.tachiyomi.data.vault.staging.writeStoredCbz
import eu.kanade.tachiyomi.data.vault.webdav.VaultWebDav
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LocalVaultImportChapter
import tachiyomi.domain.vault.model.LocalVaultImportManga
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueRefresh
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultCover
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultMangaManifestPointer
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultRootManifest
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.File
import java.nio.file.Path

class LocalVaultChapterPublisherTest {

    @TempDir
    lateinit var tempDir: Path

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
    private val vaultIdentity = ContentVaultIdentity("vault-1")

    @Test
    fun `create-new publish writes manga and root manifests`() = runTest {
        val webDav = FakeWebDav()
        webDav.files[rootPath()] = codec.encodeRoot(rootManifest())

        val result = publisher().publish(
            webDav = webDav,
            config = config,
            vaultIdentity = vaultIdentity,
            expectedVaultIdentity = vaultIdentity.value,
            importManga = importManga(),
            localChapter = scannedChapter("Chapter 1.cbz"),
            coverFile = null,
            target = LocalVaultActiveTarget.CreateNew(mangaIdentity = "new-manga", manifestPath = "manga/new.json"),
            allowReplacement = false,
            stagingRoot = stagingRoot(),
            localSourceName = "Local source",
            progressPhase = {},
        )

        result.mangaIdentity shouldBe VaultIdentity("new-manga")
        result.replaced shouldBe false
        result.target shouldBe LocalVaultActiveTarget.Created("new-manga", "manga/new.json")
        webDav.files.keys shouldContain "vault/manga/new.json"
        webDav.promotes.single().first.startsWith("vault/.staging/add-to-vault/") shouldBe true
        webDav.promotes.single().second.startsWith("vault/content/new-manga/") shouldBe true
        val root = webDav.root()
        root.summary.mangaCount shouldBe 1
        root.summary.chapterCount shouldBe 1
        root.manga.single().identity shouldBe "new-manga"
        webDav.manga("manga/new.json").chapters.single().title shouldBe "Chapter 1"
    }

    @Test
    fun `initial cover promote failure remains non-fatal`() = runTest {
        val webDav = FakeWebDav(failingPromoteFinalPathPart = "/cover/")
        webDav.files[rootPath()] = codec.encodeRoot(rootManifest())

        publisher().publish(
            webDav = webDav,
            config = config,
            vaultIdentity = vaultIdentity,
            expectedVaultIdentity = vaultIdentity.value,
            importManga = importManga(),
            localChapter = scannedChapter("Chapter 1.cbz"),
            coverFile = coverFile("cover.jpg"),
            target = LocalVaultActiveTarget.CreateNew(mangaIdentity = "new-manga", manifestPath = "manga/new.json"),
            allowReplacement = false,
            stagingRoot = stagingRoot(),
            localSourceName = "Local source",
            progressPhase = {},
        )

        val manga = webDav.manga("manga/new.json")
        manga.cover shouldBe null
        manga.chapters.single().title shouldBe "Chapter 1"
        webDav.promotes.count { it.second.contains("/cover/") } shouldBe 1
        webDav.promotes.count { it.second.contains("content/new-manga/") && !it.second.contains("/cover/") } shouldBe 1
        webDav.deletes.any { it.contains(".staging/add-to-vault") && it.endsWith(".jpg") } shouldBe true
        webDav.deletes.any { it.contains("content/new-manga/cover/") } shouldBe true
    }

    @Test
    fun `replacement publish preserves chapter identity and invalidates cache`() = runTest {
        val repository = FakeVaultRepository()
        repository.vault = contentVault()
        repository.manga += vaultManga(id = 10, identity = "manga-1")
        repository.chapters += vaultChapter(id = 99, mangaId = 10, identity = "chapter-1")
        val webDav = FakeWebDav()
        val existingManga = mangaManifest(
            mangaIdentity = "manga-1",
            chapters = listOf(
                manifestChapter(
                    identity = "chapter-1",
                    title = "Old title",
                    path = "content/manga-1/chapter-1/Chapter 1.cbz",
                ),
            ),
        )
        webDav.files[rootPath()] = codec.encodeRoot(
            rootManifest(
                manga = listOf(VaultMangaManifestPointer("manga-1", "manga/one.json", "Manga", "rev", 1, 1)),
                chapterCount = 1,
            ),
        )
        webDav.files["vault/manga/one.json"] = codec.encodeManga(existingManga)

        val result = publisher(repository = repository).publish(
            webDav = webDav,
            config = config,
            vaultIdentity = vaultIdentity,
            expectedVaultIdentity = vaultIdentity.value,
            importManga = importManga(),
            localChapter = scannedChapter("Chapter 1.cbz"),
            coverFile = null,
            target = LocalVaultActiveTarget.Existing(
                vaultManga(10, "manga-1"),
                LocalVaultImportTarget.Reason.USER_SELECTED,
            ),
            allowReplacement = true,
            stagingRoot = stagingRoot(),
            localSourceName = "Local source",
            progressPhase = {},
        )

        result.replaced shouldBe true
        repository.deletedCacheStates shouldContainExactly listOf(99L)
        val updatedChapter = webDav.manga("manga/one.json").chapters.single()
        updatedChapter.identity shouldBe "chapter-1"
        updatedChapter.content.path shouldBe
            webDav.promotes.single { it.second.contains("content/manga-1/") }.second.removePrefix("vault/")
        webDav.deletes shouldContain "vault/content/manga-1/chapter-1/Chapter 1.cbz"
    }

    @Test
    fun `unconfirmed duplicate remains per-chapter failure`() = runTest {
        val webDav = FakeWebDav()
        webDav.files[rootPath()] = codec.encodeRoot(
            rootManifest(manga = listOf(VaultMangaManifestPointer("manga-1", "manga/one.json", "Manga", "rev", 1, 1))),
        )
        webDav.files["vault/manga/one.json"] = codec.encodeManga(
            mangaManifest(
                chapters = listOf(
                    manifestChapter(
                        identity = "chapter-1",
                        title = "Different title",
                        path = "content/manga-1/chapter-1/Chapter 1.cbz",
                    ),
                ),
            ),
        )

        val error = shouldThrow<IllegalStateException> {
            publisher().publish(
                webDav = webDav,
                config = config,
                vaultIdentity = vaultIdentity,
                expectedVaultIdentity = vaultIdentity.value,
                importManga = importManga(),
                localChapter = scannedChapter("Chapter 1.cbz"),
                coverFile = null,
                target = LocalVaultActiveTarget.Existing(
                    vaultManga(1, "manga-1"),
                    LocalVaultImportTarget.Reason.USER_SELECTED,
                ),
                allowReplacement = false,
                stagingRoot = stagingRoot(),
                localSourceName = "Local source",
                progressPhase = {},
            )
        }

        error.message shouldBe "unconfirmed_duplicate"
    }

    @Test
    fun `root publish failure rolls back manga manifest and uploaded content`() = runTest {
        val webDav = FakeWebDav(failingPuts = mutableSetOf(rootPath()))
        webDav.files[rootPath()] = codec.encodeRoot(rootManifest())

        val error = shouldThrow<LocalImportGlobalFailure> {
            publisher().publish(
                webDav = webDav,
                config = config,
                vaultIdentity = vaultIdentity,
                expectedVaultIdentity = vaultIdentity.value,
                importManga = importManga(),
                localChapter = scannedChapter("Chapter 1.cbz"),
                coverFile = null,
                target = LocalVaultActiveTarget.CreateNew(
                    mangaIdentity = "new-manga",
                    manifestPath = "manga/new.json",
                ),
                allowReplacement = false,
                stagingRoot = stagingRoot(),
                localSourceName = "Local source",
                progressPhase = {},
            )
        }

        error.category shouldBe "publish"
        webDav.deletes shouldContain "vault/manga/new.json"
        webDav.deletes.any { it.startsWith("vault/content/new-manga/") } shouldBe true
    }

    @Test
    fun `root identity mismatch is global failure`() = runTest {
        val webDav = FakeWebDav()
        webDav.files[rootPath()] = codec.encodeRoot(rootManifest(identity = "other-vault"))

        val error = shouldThrow<LocalImportGlobalFailure> {
            publisher().publish(
                webDav = webDav,
                config = config,
                vaultIdentity = vaultIdentity,
                expectedVaultIdentity = vaultIdentity.value,
                importManga = importManga(),
                localChapter = scannedChapter("Chapter 1.cbz"),
                coverFile = null,
                target = LocalVaultActiveTarget.CreateNew(
                    mangaIdentity = "new-manga",
                    manifestPath = "manga/new.json",
                ),
                allowReplacement = false,
                stagingRoot = stagingRoot(),
                localSourceName = "Local source",
                progressPhase = {},
            )
        }

        error.category shouldBe "identity"
        webDav.putFiles shouldBe emptyList()
    }

    @Test
    fun `existing target missing from root is global failure`() = runTest {
        val webDav = FakeWebDav()
        webDav.files[rootPath()] = codec.encodeRoot(rootManifest())

        val error = shouldThrow<LocalImportGlobalFailure> {
            publisher().publish(
                webDav = webDav,
                config = config,
                vaultIdentity = vaultIdentity,
                expectedVaultIdentity = vaultIdentity.value,
                importManga = importManga(),
                localChapter = scannedChapter("Chapter 1.cbz"),
                coverFile = null,
                target = LocalVaultActiveTarget.Existing(
                    vaultManga(1, "missing"),
                    LocalVaultImportTarget.Reason.USER_SELECTED,
                ),
                allowReplacement = false,
                stagingRoot = stagingRoot(),
                localSourceName = "Local source",
                progressPhase = {},
            )
        }

        error.category shouldBe "target"
        webDav.files.keys.any { it.contains("missing") } shouldBe false
    }

    @Test
    fun `pre-publish failure does not create empty vault manga`() = runTest {
        val webDav = FakeWebDav()
        webDav.files[rootPath()] = codec.encodeRoot(rootManifest())
        val chapterDir = tempDir.resolve("empty").toFile().apply { mkdirs() }
        chapterDir.resolve("notes.txt").writeText("no pages")

        val error = shouldThrow<IllegalArgumentException> {
            publisher().publish(
                webDav = webDav,
                config = config,
                vaultIdentity = vaultIdentity,
                expectedVaultIdentity = vaultIdentity.value,
                importManga = importManga(),
                localChapter = scannedChapter("empty", file = chapterDir.toUniFile()),
                coverFile = null,
                target = LocalVaultActiveTarget.CreateNew(
                    mangaIdentity = "new-manga",
                    manifestPath = "manga/new.json",
                ),
                allowReplacement = false,
                stagingRoot = stagingRoot(),
                localSourceName = "Local source",
                progressPhase = {},
            )
        }

        error.message shouldBe "empty_pages"
        webDav.root().manga shouldBe emptyList()
        webDav.files.keys shouldContainExactly listOf(rootPath())
        webDav.putFiles shouldBe emptyList()
    }

    private fun publisher(
        repository: FakeVaultRepository = FakeVaultRepository(),
        operationQueueDrainer: VaultOperationQueueDrainer = FakeOperationQueueDrainer(),
    ): LocalVaultChapterPublisher {
        val preferences = ContentVaultPreferences(InMemoryPreferenceStore()).apply {
            configuredVaultIdentity.set(vaultIdentity.value)
        }
        return LocalVaultChapterPublisher(
            json = json,
            repository = repository,
            preferences = preferences,
            chapterStager = LocalVaultChapterStager(
                isReadablePageFile = { false },
                imageExtension = { null },
                splitTallImage = { _, _, _ -> },
            ),
            operationQueueDrainer = operationQueueDrainer,
            publishGate = VaultManifestPublishGate(),
        )
    }

    private fun rootPath() = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)

    private fun stagingRoot() = tempDir.resolve("staging").toFile().apply { mkdirs() }

    private fun chapterFile(name: String): UniFile {
        val file = tempDir.resolve(name).toFile()
        UniFile.fromFile(file)?.openOutputStream().use { output ->
            writeStoredCbz(
                output ?: error("missing output"),
                listOf(CbzEntry("001.jpg") { byteArrayOf(1, 2, 3).inputStream() }),
            )
        }
        return UniFile.fromFile(file) ?: error("missing chapter file")
    }

    private fun coverFile(name: String): UniFile {
        val file = tempDir.resolve(name).toFile()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        return UniFile.fromFile(file) ?: error("missing cover file")
    }

    private fun scannedChapter(
        sourceFileName: String,
        file: UniFile = chapterFile(sourceFileName),
    ): ScannedLocalVaultChapter {
        val digest = if (file.isDirectory) null else file.digest()
        return ScannedLocalVaultChapter(
            file = file,
            chapter = LocalVaultImportChapter(
                selectionId = sourceFileName,
                sourceFileName = sourceFileName,
                title = sourceFileName.substringBeforeLast('.'),
                chapterNumber = 1.0,
                volumeNumber = null,
                scanlator = null,
                sourceOrder = 0,
                contentFormat = VaultChapterContentFormat.CBZ,
                sizeBytes = digest?.sizeBytes ?: 0,
                checksumSha256 = digest?.sha256 ?: "preview",
                dateUpload = 123,
                requiresLocalCbzConversion = file.isDirectory,
            ),
        )
    }

    private fun importManga() = LocalVaultImportManga(
        localMangaId = 7,
        localMangaIdentity = "local-manga",
        title = "Manga",
        metadata = VaultMetadata("Manga", null, null, null, VaultMangaStatus.UNKNOWN),
    )

    private fun contentVault() = ContentVault(
        id = 1,
        identity = vaultIdentity,
        displayName = "Vault",
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        rootRevision = VaultRevision("root", 1),
        writerId = null,
        lastCatalogueRefreshAt = null,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun vaultManga(id: Long, identity: String) = VaultManga(
        id = id,
        vaultId = 1,
        identity = VaultIdentity(identity),
        metadata = VaultMetadata("Manga", null, null, null, VaultMangaStatus.UNKNOWN),
        sortKey = "manga",
        coverId = null,
        revision = VaultRevision("manga", 1),
        createdAt = 1,
        updatedAt = 1,
    )

    private fun vaultChapter(id: Long, mangaId: Long, identity: String) = VaultChapter(
        id = id,
        mangaId = mangaId,
        identity = VaultIdentity(identity),
        title = "Chapter 1",
        chapterNumber = 1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 0,
        content = VaultChapterContent("content/old.cbz", VaultChapterContentFormat.CBZ, 1, "old"),
        revision = VaultRevision("chapter", 1),
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun rootManifest(
        identity: String = vaultIdentity.value,
        manga: List<VaultMangaManifestPointer> = emptyList(),
        chapterCount: Long = 0,
    ) = VaultRootManifest(
        identity = identity,
        displayName = "Vault",
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        revisionId = "root-rev",
        revisionNumber = 1,
        writerId = null,
        summary = VaultCatalogueSummary(
            mangaCount = manga.size.toLong(),
            chapterCount = chapterCount,
            labelCount = 0,
            updatedAt = 1,
        ),
        manga = manga,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun mangaManifest(
        mangaIdentity: String = "manga-1",
        chapters: List<VaultManifestChapter> = emptyList(),
    ) = VaultMangaManifest(
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        vaultIdentity = vaultIdentity.value,
        mangaIdentity = mangaIdentity,
        revisionId = "manga-rev",
        revisionNumber = 1,
        metadata = VaultManifestMetadata(title = "Manga"),
        chapters = chapters,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun manifestChapter(
        identity: String,
        title: String,
        path: String = "content/manga-1/$identity/$identity.cbz",
    ) = VaultManifestChapter(
        identity = identity,
        title = title,
        chapterNumber = 1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 0,
        content = VaultManifestChapterContent(
            path = path,
            format = VaultChapterContentFormat.CBZ,
            integrity = VaultContentIntegrity(1, "old"),
        ),
        revisionId = "chapter-rev",
        revisionNumber = 1,
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private inner class FakeWebDav(
        val files: MutableMap<String, String> = mutableMapOf(),
        val failingPuts: MutableSet<String> = mutableSetOf(),
        val failingPromoteFinalPathPart: String? = null,
    ) : VaultWebDav {
        val putFiles = mutableListOf<String>()
        val promotes = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<String>()
        private val fileBytes = mutableMapOf<String, ByteArray>()

        override suspend fun get(path: String): String? = files[path]

        override suspend fun getBytes(path: String): ByteArray? = fileBytes[path]

        override suspend fun put(path: String, body: String): Boolean {
            if (path in failingPuts) return false
            files[path] = body
            return true
        }

        override suspend fun putFile(path: String, file: UniFile): Boolean {
            putFiles += path
            file.openInputStream().use { input ->
                fileBytes[path] = input.readBytes()
            }
            return true
        }

        override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean {
            putFiles += path
            fileBytes[path] = bytes
            return true
        }

        override suspend fun createDirectory(path: String): Boolean = true

        override suspend fun delete(path: String): Boolean {
            deletes += path
            files.remove(path)
            return true
        }

        override suspend fun promote(stagedPath: String, finalPath: String): Boolean {
            promotes += stagedPath to finalPath
            if (failingPromoteFinalPathPart != null && finalPath.contains(failingPromoteFinalPathPart)) {
                return false
            }
            fileBytes[finalPath] = fileBytes.remove(stagedPath) ?: return false
            return true
        }

        fun root(): VaultRootManifest {
            val result = codec.decodeRoot(files.getValue(rootPath()))
            return (result as VaultManifestReadResult.Success).manifest
        }

        fun manga(path: String): VaultMangaManifest {
            val result = codec.decodeManga(files.getValue("vault/$path"))
            return (result as VaultManifestReadResult.Success).manifest
        }
    }

    private class FakeVaultRepository : VaultRepository {
        var vault: ContentVault? = null
        val manga = mutableListOf<VaultManga>()
        val chapters = mutableListOf<VaultChapter>()
        val deletedCacheStates = mutableListOf<Long>()

        override fun getVaultsAsFlow(): Flow<List<ContentVault>> = emptyFlow()
        override suspend fun getVaultByIdentity(identity: ContentVaultIdentity): ContentVault? =
            vault?.takeIf { it.identity == identity }
        override suspend fun upsertVault(vault: ContentVault): Long = unsupported()
        override fun getMangaAsFlow(vaultId: Long): Flow<List<VaultManga>> = emptyFlow()
        override suspend fun getManga(vaultId: Long): List<VaultManga> = manga.filter { it.vaultId == vaultId }
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
        override suspend fun upsertCacheState(state: VaultChapterCacheState) = Unit
        override suspend fun getCacheState(chapterId: Long): VaultChapterCacheState? = null
        override suspend fun deleteCacheStates(chapterIds: List<Long>) {
            deletedCacheStates += chapterIds
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
        override suspend fun updateImportRequestActiveTarget(
            id: Long,
            activeMangaIdentity: VaultIdentity,
            activeManifestPath: String,
            updatedAt: Long,
        ) = Unit
        override suspend fun markImportRequestChapterCompleted(
            requestId: Long,
            selectionId: String,
            isReplaced: Boolean,
            processedAt: Long,
        ) = Unit
        override suspend fun markImportRequestChapterFailed(
            requestId: Long,
            selectionId: String,
            failureCategory: String,
            processedAt: Long,
        ) = Unit
        override suspend fun deleteImportRequest(id: Long) = Unit
        override suspend fun upsertManifestSnapshot(snapshot: VaultManifestSnapshot): Long = unsupported()
        override suspend fun refreshCatalogue(refresh: VaultCatalogueRefresh): Long = unsupported()
        override suspend fun deleteMangaLocalState(mangaId: Long) = Unit
        override fun getTransferJobsForVaultAsFlow(vaultId: Long): Flow<List<VaultTransferJob>> = emptyFlow()
        override suspend fun getTransferJobsForVault(vaultId: Long): List<VaultTransferJob> = emptyList()
        override fun getTransferJobsForMangaAsFlow(mangaId: Long): Flow<List<VaultTransferJob>> = emptyFlow()
        override suspend fun getActiveTransferJobsForOperationKey(operationKey: String): List<VaultTransferJob> =
            emptyList()
        override suspend fun getTransferJobsByState(states: List<VaultTransferState>): List<VaultTransferJob> =
            emptyList()
        override suspend fun getTransferJob(id: Long): VaultTransferJob? = null
        override suspend fun upsertTransferJob(job: VaultTransferJob): Long = unsupported()
        override suspend fun cancelInterruptedCaptureTransferJobsForImportRequest(
            importRequestId: Long,
            completedAt: Long,
        ) = Unit

        private fun unsupported(): Nothing = error("Not used by this test")
    }

    private class FakeOperationQueueDrainer : VaultOperationQueueDrainer {
        val drainedIdentities = mutableListOf<ContentVaultIdentity>()

        override suspend fun waitUntilDrained(identity: ContentVaultIdentity) {
            drainedIdentities += identity
        }
    }

    private fun File.toUniFile(): UniFile = UniFile.fromFile(this) ?: error("test file")
}
