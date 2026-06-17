package eu.kanade.tachiyomi.data.vault.localimport

import android.app.Application
import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.add.AddToVaultProgressPhase
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultActiveTarget
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultCaptureResult
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultCaptureService
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultChapterPublishResult
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultChapterPublisherBoundary
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultMangaScan
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultMangaScannerBoundary
import eu.kanade.tachiyomi.data.vault.localimport.ScannedLocalVaultChapter
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshResult
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import eu.kanade.tachiyomi.data.vault.webdav.LibraryVaultCaptureWebDav
import eu.kanade.tachiyomi.data.vault.webdav.LibraryVaultCaptureWebDavFactoryBoundary
import eu.kanade.tachiyomi.data.vault.webdav.VaultWebDav
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LibraryVaultCaptureManga
import tachiyomi.domain.vault.model.LibraryVaultCaptureTarget
import tachiyomi.domain.vault.model.LocalVaultImportChapter
import tachiyomi.domain.vault.model.LocalVaultImportManga
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.VaultCatalogueRefresh
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultCover
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultImportRequestChapterState
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.File
import java.nio.file.Path

class AddToVaultServiceResultSemanticsTest {

    @TempDir
    lateinit var tempDir: Path

    private val vaultIdentity = ContentVaultIdentity("vault-1")
    private val config = WebDavVaultConfig(
        serverUrl = "https://example.invalid",
        username = "user",
        password = "password",
        rootPath = "vault",
    )

    @Test
    fun `local explicit missing selection returns upload failed and records transfer failure`() = runTest {
        val repository = FakeVaultRepository(vault = contentVault())
        val scanner = FakeLocalScanner(
            scan = LocalVaultMangaScan(
                manga = localImportManga(),
                chapters = emptyList(),
                coverFile = null,
            ),
        )
        val service = localService(repository = repository, scanner = scanner)

        val result = service.import(
            localManga = manga(),
            selectedChapterIds = setOf("missing"),
            createNew = true,
        )

        result shouldBe LocalVaultImportResult.UploadFailed
        repository.transferJobs.single().state shouldBe VaultTransferState.FAILED
        repository.transferJobs.single().failedCount shouldBe 1
        repository.transferJobs.single().failureReason shouldBe "missing_chapter"
    }

    @Test
    fun `local missing selection plus published chapter returns imported partial success`() = runTest {
        val repository = FakeVaultRepository(vault = contentVault())
        val scanner = FakeLocalScanner(
            scan = LocalVaultMangaScan(
                manga = localImportManga(),
                chapters = listOf(scannedLocalChapter("present")),
                coverFile = null,
            ),
        )
        val service = localService(
            repository = repository,
            scanner = scanner,
            publisher = FakeLocalPublisher(
                LocalVaultChapterPublishResult(
                    target = LocalVaultActiveTarget.Created("manga-1", "manga/one.json"),
                    mangaIdentity = VaultIdentity("manga-1"),
                    replaced = false,
                ),
            ),
        )

        val result = service.import(
            localManga = manga(),
            selectedChapterIds = setOf("present", "missing"),
            createNew = true,
        )

        result shouldBe LocalVaultImportResult.Imported(
            mangaIdentity = VaultIdentity("manga-1"),
            addedChapterCount = 1,
            replacedChapterCount = 0,
            failedChapterCount = 1,
        )
        repository.transferJobs.single().state shouldBe VaultTransferState.PARTIALLY_SUCCEEDED
        repository.transferJobs.single().failedCount shouldBe 1
        repository.transferJobs.single().failureReason shouldBe null
    }

    @Test
    fun `local successful publish refreshes index before persisting target hint`() = runTest {
        val repository = FakeVaultRepository(vault = contentVault())
        val scanner = FakeLocalScanner(
            scan = LocalVaultMangaScan(
                manga = localImportManga(),
                chapters = listOf(scannedLocalChapter("present")),
                coverFile = null,
            ),
        )
        val service = localService(
            repository = repository,
            scanner = scanner,
            refreshService = FakeRefresher {
                repository.events += "refresh"
                repository.manga = listOf(vaultManga(id = 42, identity = "manga-1"))
            },
            publisher = FakeLocalPublisher(
                LocalVaultChapterPublishResult(
                    target = LocalVaultActiveTarget.Created("manga-1", "manga/one.json"),
                    mangaIdentity = VaultIdentity("manga-1"),
                    replaced = false,
                ),
            ),
        )

        service.import(
            localManga = manga(),
            selectedChapterIds = setOf("present"),
            createNew = true,
        )

        repository.importTargetHints.single().vaultMangaId shouldBe 42
        repository.events shouldBe listOf("refresh", "hint:42")
    }

    @Test
    fun `capture explicit missing selection returns upload failed and records transfer failure`() = runTest {
        val repository = FakeVaultRepository(vault = contentVault())
        val service = captureService(
            repository = repository,
            chapterRepository = FakeChapterRepository(emptyList()),
        )

        val result = service.capture(
            manga = manga().copy(favorite = true, source = 100),
            request = repository.setImportRequest(captureRequest("missing")),
        )

        result shouldBe LibraryVaultCaptureResult.UploadFailed
        repository.transferJobs.single().state shouldBe VaultTransferState.FAILED
        repository.transferJobs.single().failedCount shouldBe 1
        repository.transferJobs.single().failureReason shouldBe "missing_chapter"
    }

    @Test
    fun `capture missing selection plus published chapter returns captured partial success`() = runTest {
        val repository = FakeVaultRepository(vault = contentVault())
        val service = captureService(
            repository = repository,
            chapterRepository = FakeChapterRepository(listOf(chapter("present"))),
            publisher = FakeLibraryPublisher(
                LibraryVaultChapterPublishResult(
                    target = LibraryVaultActiveTarget.Created("manga-1", "manga/one.json"),
                    mangaIdentity = VaultIdentity("manga-1"),
                    replaced = false,
                ),
            ),
        )

        val result = service.capture(
            manga = manga().copy(favorite = true, source = 100),
            request = repository.setImportRequest(captureRequest("present", "missing")),
        )

        result shouldBe LibraryVaultCaptureResult.Captured(
            addedChapterCount = 1,
            replacedChapterCount = 0,
            failedChapterCount = 1,
        )
        repository.transferJobs.single().state shouldBe VaultTransferState.PARTIALLY_SUCCEEDED
        repository.transferJobs.single().failedCount shouldBe 1
        repository.transferJobs.single().failureReason shouldBe null
    }

    @Test
    fun `capture successful publish refreshes index before persisting target hint`() = runTest {
        val repository = FakeVaultRepository(vault = contentVault())
        val service = captureService(
            repository = repository,
            chapterRepository = FakeChapterRepository(listOf(chapter("present"))),
            refreshService = FakeRefresher {
                repository.events += "refresh"
                repository.manga = listOf(vaultManga(id = 84, identity = "manga-1"))
            },
            publisher = FakeLibraryPublisher(
                LibraryVaultChapterPublishResult(
                    target = LibraryVaultActiveTarget.Created("manga-1", "manga/one.json"),
                    mangaIdentity = VaultIdentity("manga-1"),
                    replaced = false,
                ),
            ),
        )

        service.capture(
            manga = manga().copy(favorite = true, source = 100),
            request = repository.setImportRequest(captureRequest("present")),
        )

        repository.importTargetHints.single().vaultMangaId shouldBe 84
        repository.events shouldBe listOf("refresh", "hint:84")
    }

    @Test
    fun `capture restart skips processed request chapters and includes them in final counts`() = runTest {
        val repository = FakeVaultRepository(vault = contentVault())
        val publisher = FakeLibraryPublisher(
            LibraryVaultChapterPublishResult(
                target = LibraryVaultActiveTarget.Created("manga-1", "manga/one.json"),
                mangaIdentity = VaultIdentity("manga-1"),
                replaced = true,
            ),
        )
        val service = captureService(
            repository = repository,
            chapterRepository = FakeChapterRepository(
                listOf(chapter("completed"), chapter("failed"), chapter("pending")),
            ),
            publisher = publisher,
        )
        val request = captureRequest("completed", "failed", "pending").copy(
            chapters = listOf(
                captureRequestChapter("completed", 0).copy(
                    state = VaultImportRequestChapterState.COMPLETED,
                    isReplaced = false,
                    processedAt = 10,
                ),
                captureRequestChapter("failed", 1).copy(
                    state = VaultImportRequestChapterState.FAILED,
                    failureCategory = "upload",
                    processedAt = 11,
                ),
                captureRequestChapter("pending", 2),
            ),
        )

        val result = service.capture(
            manga = manga().copy(favorite = true, source = 100),
            request = repository.setImportRequest(request),
        )

        result shouldBe LibraryVaultCaptureResult.Captured(
            addedChapterCount = 1,
            replacedChapterCount = 1,
            failedChapterCount = 1,
        )
        publisher.publishedChapterUrls shouldBe listOf("pending")
        repository.transferJobs.single().state shouldBe VaultTransferState.PARTIALLY_SUCCEEDED
        repository.transferJobs.single().addedCount shouldBe 1
        repository.transferJobs.single().replacedCount shouldBe 1
        repository.transferJobs.single().failedCount shouldBe 1
    }

    @Test
    fun `capture restart uses persisted create new target and interrupts old running job`() = runTest {
        val repository = FakeVaultRepository(vault = contentVault())
        repository.transferJobs += transferJob(
            id = 5,
            importRequestId = 99,
            state = VaultTransferState.RUNNING,
        )
        val publisher = FakeLibraryPublisher()
        val service = captureService(
            repository = repository,
            chapterRepository = FakeChapterRepository(listOf(chapter("present"))),
            publisher = publisher,
        )

        service.capture(
            manga = manga().copy(favorite = true, source = 100),
            request = repository.setImportRequest(
                captureRequest("present").copy(
                    activeMangaIdentity = VaultIdentity("persisted-manga"),
                    activeManifestPath = "manga/persisted.json",
                ),
            ),
        )

        publisher.targets.single() shouldBe LibraryVaultActiveTarget.Created(
            mangaIdentity = "persisted-manga",
            manifestPath = "manga/persisted.json",
        )
        repository.transferJobs.first { it.id == 5L }.state shouldBe VaultTransferState.CANCELLED
        repository.transferJobs.first { it.id == 5L }.failureReason shouldBe "interrupted"
    }

    private fun localService(
        repository: FakeVaultRepository,
        scanner: LocalVaultMangaScannerBoundary,
        refreshService: VaultCatalogueRefresher = FakeRefresher(),
        publisher: LocalVaultChapterPublisherBoundary = FakeLocalPublisher(),
    ): LocalVaultImportService {
        val app = mockk<Application> {
            every { cacheDir } returns tempDir.resolve("cache").toFile().apply { mkdirs() }
        }
        val networkHelper = mockk<NetworkHelper> {
            every { nonCloudflareClient } returns OkHttpClient()
        }
        return LocalVaultImportService(
            app = app,
            networkHelper = networkHelper,
            repository = repository,
            preferences = preferences(),
            refreshService = refreshService,
            scanner = scanner,
            chapterPublisher = publisher,
        )
    }

    private fun captureService(
        repository: FakeVaultRepository,
        chapterRepository: ChapterRepository,
        refreshService: VaultCatalogueRefresher = FakeRefresher(),
        publisher: LibraryVaultChapterPublisherBoundary = FakeLibraryPublisher(),
    ): LibraryVaultCaptureService {
        val context = mockk<Context> {
            every { cacheDir } returns tempDir.resolve("cache").toFile().apply { mkdirs() }
        }
        return LibraryVaultCaptureService(
            context = context,
            webDavFactory = FakeCaptureWebDavFactory(),
            repository = repository,
            preferences = preferences(),
            sourceManager = FakeSourceManager(source()),
            refreshService = refreshService,
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            chapterPublisher = publisher,
        )
    }

    private fun preferences() = ContentVaultPreferences(InMemoryPreferenceStore()).apply {
        setWebDavConfig(config, vaultIdentity)
    }

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

    private fun manga() = Manga.create().copy(
        id = 7,
        source = 100,
        favorite = true,
        url = "/manga",
        title = "Manga",
        status = 0,
    )

    private fun chapter(url: String) = Chapter.create().copy(
        id = url.hashCode().toLong(),
        mangaId = 7,
        url = url,
        name = url,
        chapterNumber = 1.0,
        sourceOrder = 0,
        dateUpload = 123,
    )

    private fun captureRequest(vararg selectionIds: String) = VaultImportRequest(
        id = 99,
        mangaId = 7,
        workflow = VaultImportRequestWorkflow.LIBRARY_CAPTURE,
        targetMangaId = null,
        createNewTitle = "Manga",
        createdAt = 1,
        updatedAt = 1,
        chapters = selectionIds.mapIndexed { index, selectionId -> captureRequestChapter(selectionId, index.toLong()) },
    )

    private fun captureRequestChapter(selectionId: String, sortOrder: Long) = VaultImportRequestChapter(
        chapterId = null,
        selectionId = selectionId,
        sortOrder = sortOrder,
        allowReplacement = false,
    )

    private fun localImportManga() = LocalVaultImportManga(
        localMangaId = 7,
        localMangaIdentity = "/manga",
        title = "Manga",
        metadata = VaultMetadata("Manga", null, null, null, VaultMangaStatus.UNKNOWN),
    )

    private fun scannedLocalChapter(selectionId: String): ScannedLocalVaultChapter {
        val file = tempDir.resolve("$selectionId.cbz").toFile().apply { writeBytes(byteArrayOf(1)) }
        return ScannedLocalVaultChapter(
            file = UniFile.fromFile(file) ?: error("missing file"),
            chapter = LocalVaultImportChapter(
                selectionId = selectionId,
                sourceFileName = "$selectionId.cbz",
                title = selectionId,
                chapterNumber = 1.0,
                volumeNumber = null,
                scanlator = null,
                sourceOrder = 0,
                contentFormat = VaultChapterContentFormat.CBZ,
                sizeBytes = 1,
                checksumSha256 = "checksum",
                dateUpload = 123,
                requiresLocalCbzConversion = false,
            ),
        )
    }

    private fun vaultManga(id: Long, identity: String) = VaultManga.create(
        vaultId = 1,
        identity = VaultIdentity(identity),
        metadata = VaultMetadata("Manga", null, null, null, VaultMangaStatus.UNKNOWN),
        revision = VaultRevision("manga", 1),
        now = 1,
    ).copy(id = id)

    private fun source(): HttpSource = mockk {
        every { id } returns 100
    }

    private class FakeLocalScanner(
        private val scan: LocalVaultMangaScan?,
    ) : LocalVaultMangaScannerBoundary {
        override fun localSourceName(): String? = "Local source"

        override suspend fun scan(
            manga: Manga,
            selectedChapterIds: Set<String>?,
        ): LocalVaultMangaScan? = scan
    }

    private class FakeLocalPublisher(
        private val result: LocalVaultChapterPublishResult = LocalVaultChapterPublishResult(
            target = LocalVaultActiveTarget.Created("manga-1", "manga/one.json"),
            mangaIdentity = VaultIdentity("manga-1"),
            replaced = false,
        ),
    ) : LocalVaultChapterPublisherBoundary {
        override suspend fun publish(
            webDav: VaultWebDav,
            config: WebDavVaultConfig,
            vaultIdentity: ContentVaultIdentity,
            expectedVaultIdentity: String?,
            importManga: LocalVaultImportManga,
            localChapter: ScannedLocalVaultChapter,
            coverFile: UniFile?,
            target: LocalVaultActiveTarget,
            allowReplacement: Boolean,
            stagingRoot: File,
            localSourceName: String?,
            progressPhase: (AddToVaultProgressPhase) -> Unit,
        ): LocalVaultChapterPublishResult = result
    }

    private class FakeLibraryPublisher(
        private val result: LibraryVaultChapterPublishResult = LibraryVaultChapterPublishResult(
            target = LibraryVaultActiveTarget.Created("manga-1", "manga/one.json"),
            mangaIdentity = VaultIdentity("manga-1"),
            replaced = false,
        ),
    ) : LibraryVaultChapterPublisherBoundary {
        val publishedChapterUrls = mutableListOf<String>()
        val targets = mutableListOf<LibraryVaultActiveTarget>()

        override suspend fun publish(
            webDav: LibraryVaultCaptureWebDav,
            config: WebDavVaultConfig,
            vaultIdentity: ContentVaultIdentity,
            expectedVaultIdentity: String?,
            source: HttpSource,
            manga: Manga,
            captureManga: LibraryVaultCaptureManga,
            chapter: Chapter,
            target: LibraryVaultActiveTarget,
            stagingRoot: File,
            allowReplacement: Boolean,
            progressPhase: (AddToVaultProgressPhase) -> Unit,
        ): LibraryVaultChapterPublishResult {
            publishedChapterUrls += chapter.url
            targets += target
            return result
        }
    }

    private class FakeCaptureWebDavFactory : LibraryVaultCaptureWebDavFactoryBoundary {
        override fun create(config: WebDavVaultConfig): LibraryVaultCaptureWebDav = object : LibraryVaultCaptureWebDav {
            override suspend fun get(path: String): String? = null
            override suspend fun getBytes(path: String): ByteArray? = null
            override suspend fun put(path: String, body: String): Boolean = true
            override suspend fun putFile(path: String, file: UniFile): Boolean = true
            override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean = true
            override suspend fun createDirectory(path: String): Boolean = true
            override suspend fun delete(path: String): Boolean = true
            override suspend fun promote(stagedPath: String, finalPath: String): Boolean = true
        }
    }

    private class FakeRefresher(
        private val onRefresh: () -> Unit = {},
    ) : VaultCatalogueRefresher {
        override suspend fun refreshConfiguredVault(): VaultCatalogueRefreshResult {
            onRefresh()
            return VaultCatalogueRefreshResult.Refreshed(
                identity = ContentVaultIdentity("vault-1"),
                mangaCount = 1,
                chapterCount = 1,
            )
        }
    }

    private class FakeSourceManager(
        private val source: Source,
    ) : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources: Flow<List<CatalogueSource>> = emptyFlow()
        override fun get(sourceKey: Long): Source? = source
        override fun getOrStub(sourceKey: Long): Source = source
        override fun getOnlineSources(): List<HttpSource> = listOf(source as HttpSource)
        override fun getCatalogueSources(): List<CatalogueSource> = emptyList()
        override fun getStubSources(): List<StubSource> = emptyList()
    }

    private class FakeChapterRepository(
        private val chapters: List<Chapter>,
    ) : ChapterRepository {
        override suspend fun addAll(chapters: List<Chapter>): List<Chapter> = unsupported()
        override suspend fun update(chapterUpdate: ChapterUpdate) = Unit
        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) = Unit
        override suspend fun removeChaptersWithIds(chapterIds: List<Long>) = Unit
        override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> =
            chapters.filter { it.mangaId == mangaId }
        override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()
        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = emptyFlow()
        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = emptyList()
        override suspend fun getChapterById(id: Long): Chapter? = null
        override suspend fun getChapterByMangaIdAsFlow(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): Flow<List<Chapter>> = emptyFlow()
        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? = null

        private fun <T> unsupported(): T = error("Not used by this test")
    }

    private class FakeVaultRepository(
        var vault: ContentVault?,
    ) : VaultRepository {
        var manga = emptyList<VaultManga>()
        var importRequest: VaultImportRequest? = null
        val events = mutableListOf<String>()
        val importTargetHints = mutableListOf<ImportTargetHint>()
        val transferJobs = mutableListOf<VaultTransferJob>()

        fun setImportRequest(request: VaultImportRequest): VaultImportRequest {
            importRequest = request
            return request
        }

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
        override suspend fun upsertCacheState(state: VaultChapterCacheState) = Unit
        override suspend fun getCacheState(chapterId: Long): VaultChapterCacheState? = null
        override suspend fun deleteCacheStates(chapterIds: List<Long>) = Unit
        override fun getCacheStatesForMangaAsFlow(mangaId: Long): Flow<List<VaultChapterCacheState>> = emptyFlow()
        override fun getCacheStatesForVaultAsFlow(vaultId: Long): Flow<List<VaultChapterCacheState>> = emptyFlow()
        override suspend fun getCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> = emptyList()
        override suspend fun getReadCacheStatesForVault(vaultId: Long): List<VaultChapterCacheState> = emptyList()
        override suspend fun getLocalCacheUsageBytes(vaultId: Long): Long = 0
        override suspend fun upsertImportTargetHint(hint: ImportTargetHint) {
            importTargetHints += hint
            events += "hint:${hint.vaultMangaId}"
        }
        override suspend fun getImportTargetHint(localMangaId: Long): ImportTargetHint? = null
        override fun getImportTargetHintAsFlow(localMangaId: Long): Flow<ImportTargetHint?> = emptyFlow()
        override suspend fun deleteImportTargetHint(localMangaId: Long) = Unit
        override suspend fun insertImportRequest(request: VaultImportRequest): Long = unsupported()
        override suspend fun getImportRequest(id: Long): VaultImportRequest? = importRequest?.takeIf { it.id == id }
        override suspend fun updateImportRequestActiveTarget(
            id: Long,
            activeMangaIdentity: VaultIdentity,
            activeManifestPath: String,
            updatedAt: Long,
        ) {
            importRequest = importRequest?.takeIf { it.id == id }?.copy(
                activeMangaIdentity = activeMangaIdentity,
                activeManifestPath = activeManifestPath,
                updatedAt = updatedAt,
            ) ?: importRequest
        }
        override suspend fun markImportRequestChapterCompleted(
            requestId: Long,
            selectionId: String,
            isReplaced: Boolean,
            processedAt: Long,
        ) {
            updateRequestChapter(requestId, selectionId) {
                it.copy(
                    state = VaultImportRequestChapterState.COMPLETED,
                    isReplaced = isReplaced,
                    failureCategory = null,
                    processedAt = processedAt,
                )
            }
        }
        override suspend fun markImportRequestChapterFailed(
            requestId: Long,
            selectionId: String,
            failureCategory: String,
            processedAt: Long,
        ) {
            updateRequestChapter(requestId, selectionId) {
                it.copy(
                    state = VaultImportRequestChapterState.FAILED,
                    isReplaced = false,
                    failureCategory = failureCategory,
                    processedAt = processedAt,
                )
            }
        }
        override suspend fun deleteImportRequest(id: Long) = Unit
        override suspend fun upsertManifestSnapshot(snapshot: VaultManifestSnapshot): Long = unsupported()
        override suspend fun refreshCatalogue(refresh: VaultCatalogueRefresh): Long = unsupported()
        override suspend fun deleteMangaLocalState(mangaId: Long) = Unit
        override fun getTransferJobsForVaultAsFlow(vaultId: Long): Flow<List<VaultTransferJob>> = emptyFlow()
        override suspend fun getTransferJobsForVault(vaultId: Long): List<VaultTransferJob> = transferJobs
        override suspend fun getTransferJobsByState(states: List<VaultTransferState>): List<VaultTransferJob> =
            transferJobs.filter { it.state in states }
        override suspend fun getTransferJob(id: Long): VaultTransferJob? = transferJobs.firstOrNull { it.id == id }
        override suspend fun upsertTransferJob(job: VaultTransferJob): Long {
            val id = job.id.takeIf { it != -1L } ?: ((transferJobs.maxOfOrNull { it.id } ?: 0L) + 1L)
            transferJobs.removeAll { it.id == id }
            transferJobs += job.copy(id = id)
            return id
        }
        override suspend fun cancelInterruptedCaptureTransferJobsForImportRequest(
            importRequestId: Long,
            completedAt: Long,
        ) {
            transferJobs.replaceAll { job ->
                if (
                    job.importRequestId == importRequestId &&
                    job.type == VaultTransferType.CAPTURE_PUBLISH &&
                    job.state == VaultTransferState.RUNNING
                ) {
                    job.copy(
                        state = VaultTransferState.CANCELLED,
                        failureReason = "interrupted",
                        updatedAt = completedAt,
                        completedAt = completedAt,
                    )
                } else {
                    job
                }
            }
        }

        private fun updateRequestChapter(
            requestId: Long,
            selectionId: String,
            transform: (VaultImportRequestChapter) -> VaultImportRequestChapter,
        ) {
            importRequest = importRequest?.takeIf { it.id == requestId }?.let { request ->
                request.copy(
                    chapters = request.chapters.map { chapter ->
                        if (chapter.selectionId == selectionId) transform(chapter) else chapter
                    },
                )
            } ?: importRequest
        }

        private fun unsupported(): Nothing = error("Not used by this test")
    }

    private fun transferJob(
        id: Long,
        importRequestId: Long?,
        state: VaultTransferState,
    ) = VaultTransferJob(
        id = id,
        vaultId = 1,
        chapterId = null,
        importRequestId = importRequestId,
        type = VaultTransferType.CAPTURE_PUBLISH,
        state = state,
        remotePath = null,
        localPath = null,
        stagedPath = null,
        sizeBytes = null,
        checksumSha256 = null,
        failureReason = null,
        attempts = 1,
        createdAt = 1,
        updatedAt = 1,
        startedAt = 1,
        completedAt = null,
    )
}
