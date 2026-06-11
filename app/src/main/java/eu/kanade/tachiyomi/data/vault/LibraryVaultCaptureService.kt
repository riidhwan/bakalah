package eu.kanade.tachiyomi.data.vault

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mihon.core.archive.archiveReader
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.vault.interactor.BuildLibraryVaultCapturePlan
import tachiyomi.domain.vault.interactor.duplicateTitleKey
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LibraryVaultCaptureChapter
import tachiyomi.domain.vault.model.LibraryVaultCaptureManga
import tachiyomi.domain.vault.model.LibraryVaultCapturePlan
import tachiyomi.domain.vault.model.LibraryVaultCaptureTarget
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultMangaManifestPointer
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestChapterProvenance
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestCover
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultManifestProvenance
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultRootManifest
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

class LibraryVaultCaptureService(
    private val context: Context,
    networkHelper: NetworkHelper,
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val sourceManager: SourceManager,
    private val downloadProvider: DownloadProvider,
    private val coverCache: CoverCache,
    private val refreshService: VaultCatalogueRefreshService,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {
    private val client = networkHelper.nonCloudflareClient
    private val codec = VaultManifestCodec(json)
    private val planner = BuildLibraryVaultCapturePlan()

    suspend fun capture(
        manga: Manga,
        selectedChapterIds: Set<String>,
        allowedReplacementChapterIds: Set<String> = emptySet(),
        targetMangaId: Long? = null,
        createNew: Boolean = false,
        createNewTitle: String? = null,
        progress: (LocalVaultImportProgress) -> Unit = {},
    ): LibraryVaultCaptureResult {
        if (!manga.favorite) return LibraryVaultCaptureResult.NotLibraryManga
        val source =
            sourceManager.get(manga.source) as? HttpSource ?: return LibraryVaultCaptureResult.SourceUnavailable
        val config = preferences.getWebDavConfig()
        val vault = configuredVault() ?: return LibraryVaultCaptureResult.IncompleteConfiguration
        val expectedVaultIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        val selectedChapters = getChaptersByMangaId.await(manga.id)
            .filter { it.url in selectedChapterIds }
        if (selectedChapters.isEmpty() && selectedChapterIds.isEmpty()) return LibraryVaultCaptureResult.NothingSelected
        val missingSelectedChapterIds = selectedChapterIds - selectedChapters.map { it.url }.toSet()

        val vaultManga = repository.getManga(vault.id)
        val existingChapters = repository.getChaptersForVault(vault.id).groupBy { it.mangaId }
        val captureManga = manga.toCaptureManga(source)
            .withCreateNewTitle(createNew = createNew, title = createNewTitle)
        val plan = planner.build(
            libraryManga = captureManga,
            libraryChapters = selectedChapters.map { it.toCaptureChapter() },
            vaultManga = vaultManga,
            existingChaptersByMangaId = existingChapters,
            hint = repository.getImportTargetHint(manga.id),
        )
        val target = resolveTarget(plan.target, vaultManga, targetMangaId, createNew)
            ?: return LibraryVaultCaptureResult.TargetChoiceRequired(plan)

        val now = System.currentTimeMillis()
        var job = VaultTransferJob(
            id = -1,
            vaultId = vault.id,
            chapterId = null,
            type = VaultTransferType.CAPTURE_PUBLISH,
            state = VaultTransferState.RUNNING,
            remotePath = null,
            localPath = null,
            stagedPath = null,
            sizeBytes = null,
            checksumSha256 = null,
            failureReason = null,
            attempts = 1,
            createdAt = now,
            updatedAt = now,
            startedAt = now,
            completedAt = null,
        ).let { it.copy(id = repository.upsertTransferJob(it)) }

        var added = 0
        var replaced = 0
        val failures = missingSelectedChapterIds
            .map { ChapterFailure(it, "missing_chapter") }
            .toMutableList()
        val stagingRoot = captureStagingRoot().apply {
            deleteRecursively()
            mkdirs()
        }
        val webDav = WebDavClient(config)
        val progressTotal = selectedChapters.size.coerceAtLeast(1)

        try {
            selectedChapters.forEachIndexed { index, chapter ->
                currentCoroutineContext().ensureActive()
                fun updatePhase(phase: VaultImportProgressPhase) {
                    progress(
                        LocalVaultImportProgress(
                            current = index,
                            total = progressTotal,
                            chapterTitle = chapter.name,
                            indeterminate = true,
                            phase = phase,
                        ),
                    )
                }
                updatePhase(VaultImportProgressPhase.PREPARING)
                val chapterStagingRoot = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
                val published = runCatching {
                    try {
                        publishChapter(
                            webDav = webDav,
                            config = config,
                            vault = vault,
                            expectedVaultIdentity = expectedVaultIdentity,
                            source = source,
                            manga = manga,
                            captureManga = captureManga,
                            chapter = chapter,
                            target = target,
                            stagingRoot = chapterStagingRoot,
                            allowReplacement = chapter.url in allowedReplacementChapterIds,
                            progressPhase = ::updatePhase,
                        )
                    } finally {
                        chapterStagingRoot.deleteRecursively()
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    failures += ChapterFailure(chapter.name, error.captureFailureCategory())
                    return@forEachIndexed
                }
                if (published.replaced) {
                    replaced += 1
                } else {
                    added += 1
                }
                progress(
                    LocalVaultImportProgress(
                        current = index + 1,
                        total = progressTotal,
                        chapterTitle = chapter.name,
                    ),
                )
            }
        } catch (e: CancellationException) {
            val cancelled = selectedChapters.size - added - replaced - failures.size
            repository.upsertTransferJob(
                job.copy(
                    state = VaultTransferState.CANCELLED,
                    addedCount = added.toLong(),
                    replacedCount = replaced.toLong(),
                    failedCount = failures.size.toLong(),
                    cancelledCount = cancelled.toLong(),
                    detailJson = failures.toDetailJson(),
                    updatedAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis(),
                ),
            )
            throw e
        } finally {
            stagingRoot.deleteRecursively()
        }

        val completedAt = System.currentTimeMillis()
        val state = when {
            added + replaced == 0 -> VaultTransferState.FAILED
            failures.isNotEmpty() -> VaultTransferState.PARTIALLY_SUCCEEDED
            else -> VaultTransferState.SUCCEEDED
        }
        repository.upsertTransferJob(
            job.copy(
                state = state,
                addedCount = added.toLong(),
                replacedCount = replaced.toLong(),
                failedCount = failures.size.toLong(),
                detailJson = failures.toDetailJson(),
                updatedAt = completedAt,
                completedAt = completedAt,
            ),
        )

        return LibraryVaultCaptureResult.Captured(
            addedChapterCount = added,
            replacedChapterCount = replaced,
            failedChapterCount = failures.size,
        )
    }

    private suspend fun publishChapter(
        webDav: WebDavClient,
        config: WebDavVaultConfig,
        vault: ContentVault,
        expectedVaultIdentity: String?,
        source: HttpSource,
        manga: Manga,
        captureManga: LibraryVaultCaptureManga,
        chapter: Chapter,
        target: CaptureTarget,
        stagingRoot: File,
        allowReplacement: Boolean,
        progressPhase: (VaultImportProgressPhase) -> Unit,
    ): PublishedChapter {
        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootManifest = webDav.get(rootPath)
            ?.let { decodeRootManifest(it) }
            ?: error("manifest")
        if (expectedVaultIdentity != null && rootManifest.identity != expectedVaultIdentity) {
            error("identity")
        }

        val mangaManifestPath = when (target) {
            is CaptureTarget.Existing ->
                rootManifest.manga
                    .firstOrNull { it.identity == target.manga.identity.value }
                    ?.path
                    ?: "manga/${target.manga.identity.value}.json"
            is CaptureTarget.CreateNew -> target.manifestPath
        }
        val remoteMangaManifest = when (target) {
            is CaptureTarget.Existing -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
                ?: error("target")
            is CaptureTarget.CreateNew -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
        }
        val mangaIdentity = when (target) {
            is CaptureTarget.Existing -> target.manga.identity.value
            is CaptureTarget.CreateNew -> target.mangaIdentity
        }
        val now = System.currentTimeMillis()
        val stagedChapter = stageChapter(source, manga, chapter, stagingRoot, progressPhase)
        val existingRemoteChapters = remoteMangaManifest?.chapters.orEmpty()
        val chapterDuplicateTitleKey = chapter.name.duplicateTitleKey()
        val replacement = existingRemoteChapters
            .firstOrNull { it.title.duplicateTitleKey() == chapterDuplicateTitleKey }
        if (replacement != null && !allowReplacement) {
            error("unconfirmed_duplicate")
        }
        val chapterIdentity = replacement?.identity ?: UUID.randomUUID().toString()
        val contentIdentity = if (replacement == null) chapterIdentity else UUID.randomUUID().toString()
        progressPhase(VaultImportProgressPhase.UPLOADING)
        val contentPath = uploadChapter(
            webDav = webDav,
            config = config,
            mangaIdentity = mangaIdentity,
            contentIdentity = contentIdentity,
            chapterFile = stagedChapter.file,
        )
        val manifestChapter = if (replacement != null) {
            replacement.copy(
                content = VaultManifestChapterContent(
                    path = contentPath,
                    format = VaultChapterContentFormat.CBZ,
                    integrity = VaultContentIntegrity(stagedChapter.sizeBytes, stagedChapter.checksumSha256),
                ),
                revisionId = UUID.randomUUID().toString(),
                revisionNumber = replacement.revisionNumber + 1,
                updatedAt = now,
            )
        } else {
            VaultManifestChapter(
                identity = chapterIdentity,
                title = chapter.name,
                chapterNumber = chapter.chapterNumber,
                volumeNumber = null,
                scanlator = chapter.scanlator,
                sourceOrder = 0,
                content = VaultManifestChapterContent(
                    path = contentPath,
                    format = VaultChapterContentFormat.CBZ,
                    integrity = VaultContentIntegrity(stagedChapter.sizeBytes, stagedChapter.checksumSha256),
                ),
                revisionId = UUID.randomUUID().toString(),
                revisionNumber = 1,
                dateUpload = chapter.dateUpload,
                createdAt = now,
                updatedAt = now,
            )
        }
        val replacedIdentities = setOfNotNull(replacement?.identity)
        val updatedChapters = orderCapturedChapters(
            chapters = existingRemoteChapters.filterNot { it.identity in replacedIdentities } + manifestChapter,
            replacementIdentities = replacedIdentities,
        )
        val metadata = when (target) {
            is CaptureTarget.Existing -> target.manga.metadata
            is CaptureTarget.CreateNew -> captureManga.metadata
        }
        val importedCover = remoteMangaManifest?.cover ?: runCatching {
            progressPhase(VaultImportProgressPhase.UPLOADING)
            uploadCover(
                webDav = webDav,
                config = config,
                mangaIdentity = mangaIdentity,
                cover = manga.findCaptureCover(source),
                now = now,
            )
        }.getOrNull()
        val mangaRevision = remoteMangaManifest?.revisionNumber?.plus(1) ?: 1
        val mangaRevisionId = UUID.randomUUID().toString()
        val provenance = VaultManifestChapterProvenance(
            chapterIdentity = chapterIdentity,
            sourceId = source.id,
            sourceName = source.toString(),
            sourceMangaUrl = manga.url,
            sourceChapterUrl = chapter.url,
            capturedAt = now,
        )
        val mangaManifest = VaultMangaManifest(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            vaultIdentity = rootManifest.identity,
            mangaIdentity = mangaIdentity,
            revisionId = mangaRevisionId,
            revisionNumber = mangaRevision,
            metadata = metadata.toManifestMetadata(),
            labels = remoteMangaManifest?.labels.orEmpty(),
            cover = importedCover,
            chapters = updatedChapters,
            provenance = remoteMangaManifest?.provenance ?: VaultManifestProvenance(
                importedFrom = "library-capture",
                sourceName = source.toString(),
                sourceUri = manga.url,
                importedAt = now,
            ),
            chapterProvenance = remoteMangaManifest?.chapterProvenance
                .orEmpty()
                .filterNot { it.chapterIdentity == chapterIdentity }
                .plus(provenance),
            createdAt = remoteMangaManifest?.createdAt ?: now,
            updatedAt = now,
        )

        webDav.createDirectory(config.rootPath.childPath("manga"))
        progressPhase(VaultImportProgressPhase.PUBLISHING)
        if (!webDav.put(config.rootPath.childPath(mangaManifestPath), codec.encodeManga(mangaManifest))) {
            error("publish")
        }
        val updatedPointers = rootManifest.manga
            .filterNot { it.identity == mangaIdentity }
            .plus(
                VaultMangaManifestPointer(
                    identity = mangaIdentity,
                    path = mangaManifestPath,
                    title = metadata.title,
                    revisionId = mangaRevisionId,
                    revisionNumber = mangaRevision,
                    updatedAt = now,
                ),
            )
            .sortedBy { VaultMetadata.normalizeTitle(it.title) }
        val updatedRoot = rootManifest.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = rootManifest.revisionNumber + 1,
            updatedAt = now,
            summary = VaultCatalogueSummary(
                mangaCount = updatedPointers.size.toLong(),
                chapterCount = rootManifest.summary.chapterCount -
                    (remoteMangaManifest?.chapters?.size ?: 0) +
                    mangaManifest.chapters.size,
                labelCount = rootManifest.summary.labelCount,
                updatedAt = now,
            ),
            manga = updatedPointers,
        )
        if (!webDav.put(rootPath, codec.encodeRoot(updatedRoot))) {
            rollbackPublishedMangaManifest(
                webDav = webDav,
                config = config,
                mangaManifestPath = mangaManifestPath,
                previousManifest = remoteMangaManifest,
                newContentPath = contentPath,
                newCoverPath = importedCover?.path.takeIf { remoteMangaManifest?.cover == null },
            )
            error("publish")
        }

        replacement?.content?.path?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
        if (replacement != null && target is CaptureTarget.Existing) {
            val replacedChapterIds = repository.getChapters(target.manga.id)
                .filter { it.identity.value == replacement.identity }
                .map { it.id }
            repository.deleteCacheStates(replacedChapterIds)
        }
        progressPhase(VaultImportProgressPhase.REFRESHING)
        refreshLocalIndex(vault.identity, mangaIdentity)
        repository.getVaultByIdentity(vault.identity)
            ?.let { repository.getManga(it.id) }
            ?.firstOrNull { it.identity.value == mangaIdentity }
            ?.id
            ?.let { vaultMangaId ->
                repository.upsertImportTargetHint(
                    ImportTargetHint(
                        localMangaId = manga.id,
                        localMangaIdentity = manga.url,
                        contentVaultIdentity = vault.identity,
                        sourceIdentity = captureManga.sourceIdentity,
                        vaultMangaIdentity = VaultIdentity(mangaIdentity),
                        vaultMangaId = vaultMangaId,
                        updatedAt = now,
                    ),
                )
            }
        return PublishedChapter(replaced = replacement != null)
    }

    private suspend fun stageChapter(
        source: HttpSource,
        manga: Manga,
        chapter: Chapter,
        stagingRoot: File,
        progressPhase: (VaultImportProgressPhase) -> Unit,
    ): StagedChapter = withIOContext {
        val chapterDir = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val chapterUniFile = UniFile.fromFile(chapterDir) ?: error("staging")
        try {
            val downloaded = downloadProvider.findChapterDir(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = manga.title,
                source = source,
            )
            if (downloaded != null) {
                progressPhase(VaultImportProgressPhase.COPYING_DOWNLOADED)
                runCatching {
                    copyDownloadedPages(downloaded, chapterUniFile)
                }.getOrElse {
                    chapterUniFile.listFiles().orEmpty().forEach { file -> file.delete() }
                    progressPhase(VaultImportProgressPhase.DOWNLOADING)
                    fetchSourcePages(source, chapter, chapterUniFile)
                }
            } else {
                progressPhase(VaultImportProgressPhase.DOWNLOADING)
                fetchSourcePages(source, chapter, chapterUniFile)
            }
            progressPhase(VaultImportProgressPhase.COMPRESSING)
            val entries = chapterUniFile.listFiles().orEmpty()
                .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .sortedBy { it.name.orEmpty() }
                .mapIndexed { index, file ->
                    CbzEntry(
                        name = "%03d.${file.extension ?: "jpg"}".format(Locale.ENGLISH, index + 1),
                        openInputStream = { file.openInputStream() },
                    )
                }
            require(entries.isNotEmpty()) { "empty_pages" }
            val cbzFile = UniFile.fromFile(File(stagingRoot, "${UUID.randomUUID()}.cbz")) ?: error("staging")
            cbzFile.openOutputStream().use { output ->
                writeStoredCbz(output, entries)
            }
            validateCbz(cbzFile, entries.map { it.name })
            val digest = cbzFile.digest()
            StagedChapter(cbzFile, digest.sizeBytes, digest.sha256)
        } finally {
            chapterDir.deleteRecursively()
        }
    }

    private suspend fun fetchSourcePages(
        source: HttpSource,
        chapter: Chapter,
        chapterUniFile: UniFile,
    ) {
        val pages = source.getPageList(chapter.toSChapter()).mapIndexed { index, page ->
            Page(index, page.url, page.imageUrl, page.uri)
        }
        require(pages.isNotEmpty()) { "empty_pages" }
        val digitCount = pages.size.toString().length.coerceAtLeast(3)
        pages.forEach { page ->
            if (page.imageUrl.isNullOrEmpty()) {
                page.imageUrl = source.getImageUrl(page)
            }
            val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)
            val response = source.getImage(page)
            val tmpFile = chapterUniFile.createFile("$filename.tmp") ?: error("staging")
            try {
                response.body.source().use { input ->
                    tmpFile.openOutputStream().use { output ->
                        output.write(input.readByteArray())
                    }
                }
                val extension = ImageUtil.getExtensionFromMimeType(
                    response.body.contentType()?.run { if (type == "image") "image/$subtype" else null },
                ) { tmpFile.openInputStream() }
                tmpFile.renameTo("$filename.$extension")
                splitStagedImage(chapterUniFile, filename)
            } finally {
                response.close()
            }
        }
    }

    private fun copyDownloadedPages(downloaded: UniFile, chapterUniFile: UniFile) {
        if (downloaded.isDirectory) {
            val files = downloaded.listFiles().orEmpty()
                .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .sortedBy { it.name.orEmpty() }
            require(files.isNotEmpty()) { "downloaded_copy" }
            val digitCount = files.size.toString().length.coerceAtLeast(3)
            files.forEachIndexed { index, file ->
                val filename = "%0${digitCount}d".format(Locale.ENGLISH, index + 1)
                val extension =
                    ImageUtil.findImageType { file.openInputStream() }?.extension ?: file.extension?.lowercase()
                        ?: "jpg"
                val target = chapterUniFile.createFile("$filename.$extension") ?: error("staging")
                file.openInputStream().use { input ->
                    target.openOutputStream().use { output -> input.copyTo(output) }
                }
                splitStagedImage(chapterUniFile, filename)
            }
            return
        }

        val archiveEntries = downloaded.archiveReader(context).use { reader ->
            reader.useEntries { entries ->
                entries
                    .filter { it.isFile }
                    .sortedBy { it.name }
                    .mapNotNull { entry ->
                        val bytes = reader.getInputStream(entry.name)?.use { it.readBytes() } ?: return@mapNotNull null
                        val imageType = ImageUtil.findImageType(ByteArrayInputStream(bytes))
                            ?: entry.name.imageTypeFromExtension()
                            ?: return@mapNotNull null
                        ArchivedPage(bytes = bytes, extension = imageType.extension)
                    }
                    .toList()
            }
        }
        require(archiveEntries.isNotEmpty()) { "downloaded_copy" }
        val digitCount = archiveEntries.size.toString().length.coerceAtLeast(3)
        val extracted = archiveEntries.mapIndexed { index, page ->
            val filename = "%0${digitCount}d".format(Locale.ENGLISH, index + 1)
            val target = chapterUniFile.createFile("$filename.${page.extension}") ?: error("staging")
            target.openOutputStream().use { output -> output.write(page.bytes) }
            target
        }
        extracted.forEach { file ->
            val filename = file.name.orEmpty().substringBeforeLast('.', file.name.orEmpty())
            splitStagedImage(chapterUniFile, filename)
        }
    }

    private fun splitStagedImage(chapterUniFile: UniFile, filename: String) {
        val imageFile = chapterUniFile.listFiles().orEmpty()
            .firstOrNull { it.name.orEmpty().startsWith(filename) }
            ?: error("staging")
        ImageUtil.splitTallImage(chapterUniFile, imageFile, filename)
    }

    private suspend fun uploadChapter(
        webDav: WebDavClient,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        contentIdentity: String,
        chapterFile: UniFile,
    ): String {
        val basePath = "content/$mangaIdentity/$contentIdentity"
        val remoteBasePath = config.rootPath.childPath(basePath)
        webDav.createDirectory(config.rootPath.childPath("content"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        webDav.createDirectory(remoteBasePath)
        val path = "$basePath/$contentIdentity.cbz"
        if (!webDav.putFile(config.rootPath.childPath(path), chapterFile)) {
            error("upload")
        }
        return path
    }

    private suspend fun rollbackPublishedMangaManifest(
        webDav: WebDavClient,
        config: WebDavVaultConfig,
        mangaManifestPath: String,
        previousManifest: VaultMangaManifest?,
        newContentPath: String,
        newCoverPath: String?,
    ) {
        runCatching {
            if (previousManifest != null) {
                webDav.put(config.rootPath.childPath(mangaManifestPath), codec.encodeManga(previousManifest))
            } else {
                webDav.delete(config.rootPath.childPath(mangaManifestPath))
            }
        }
        runCatching { webDav.delete(config.rootPath.childPath(newContentPath)) }
        newCoverPath?.let { path ->
            runCatching { webDav.delete(config.rootPath.childPath(path)) }
        }
    }

    private suspend fun uploadCover(
        webDav: WebDavClient,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        cover: CaptureCover?,
        now: Long,
    ): VaultManifestCover? {
        cover ?: return null
        val coverIdentity = UUID.randomUUID().toString()
        val extension = cover.extension
        val path = "content/$mangaIdentity/cover/$coverIdentity.$extension"
        val digest = cover.bytes.digest()

        webDav.createDirectory(config.rootPath.childPath("content"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity/cover"))
        if (!webDav.putBytes(config.rootPath.childPath(path), cover.bytes, cover.mediaType)) {
            error("cover_upload")
        }

        return VaultManifestCover(
            identity = coverIdentity,
            path = path,
            mediaType = cover.mediaType,
            integrity = VaultContentIntegrity(
                sizeBytes = digest.sizeBytes,
                checksumSha256 = digest.sha256,
            ),
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = 1,
            updatedAt = now,
        )
    }

    private suspend fun Manga.findCaptureCover(source: HttpSource): CaptureCover? = withContext(Dispatchers.IO) {
        listOfNotNull(
            coverCache.getCustomCoverFile(id).takeIf { it.exists() && it.isFile },
            coverCache.getCoverFile(thumbnailUrl)?.takeIf { it.exists() && it.isFile },
        ).firstNotNullOfOrNull { file ->
            file.readBytes().toCaptureCover(file.name, null)
        } ?: fetchCaptureCover(source)
    }

    private fun Manga.fetchCaptureCover(source: HttpSource): CaptureCover? {
        val coverUrl = thumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val request = Request.Builder()
                .url(coverUrl)
                .headers(source.headers)
                .build()
            source.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val mediaType = response.body.contentType()
                    ?.toString()
                    ?.takeIf { it.startsWith("image/") }
                response.body.bytes().toCaptureCover(coverUrl.substringAfterLast('/'), mediaType)
            }
        }.getOrNull()
    }

    private fun ByteArray.toCaptureCover(fileName: String?, mediaType: String?): CaptureCover? {
        val imageType = ImageUtil.findImageType(ByteArrayInputStream(this))
        val extension = imageType?.extension
            ?: mediaType.mediaTypeExtension()
            ?: fileName?.imageTypeFromExtension()?.extension
            ?: return null
        val normalizedExtension = extension.validImageExtension() ?: return null
        return CaptureCover(
            bytes = this,
            extension = normalizedExtension,
            mediaType = imageType?.mime ?: mediaType,
        )
    }

    private fun String?.mediaTypeExtension(): String? {
        return when (this) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/heif" -> "heif"
            "image/jxl" -> "jxl"
            else -> null
        }
    }

    private fun String.imageTypeFromExtension(): ImageUtil.ImageType? {
        val extension = substringAfterLast('.', "").lowercase()
        return ImageUtil.ImageType.entries.firstOrNull { it.extension == extension }
    }

    private fun ByteArray.digest(): FileDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(this)
        return FileDigest(size.toLong(), digest.digest().toHex())
    }

    private fun String.validImageExtension(): String? {
        return lowercase()
            .takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?.takeIf { extension ->
                ImageUtil.ImageType.entries.any { it.extension == extension }
            }
    }

    private data class CaptureCover(
        val bytes: ByteArray,
        val extension: String,
        val mediaType: String?,
    )

    private suspend fun configuredVault() =
        preferences.configuredVaultIdentity.get()
            .takeIf { it.isNotBlank() }
            ?.let { repository.getVaultByIdentity(ContentVaultIdentity(it)) }

    private suspend fun refreshLocalIndex(vaultIdentity: ContentVaultIdentity, mangaIdentity: String): Long {
        refreshService.refreshConfiguredVault()
        return repository.getVaultByIdentity(vaultIdentity)
            ?.let { repository.getManga(it.id) }
            ?.firstOrNull { it.identity.value == mangaIdentity }
            ?.id
            ?: -1
    }

    private fun resolveTarget(
        target: LibraryVaultCaptureTarget,
        vaultManga: List<VaultManga>,
        targetMangaId: Long?,
        createNew: Boolean,
    ): CaptureTarget? {
        if (createNew) {
            return CaptureTarget.CreateNew(
                mangaIdentity = UUID.randomUUID().toString(),
                manifestPath = "manga/${UUID.randomUUID()}.json",
            )
        }
        targetMangaId
            ?.let { id -> vaultManga.firstOrNull { it.id == id } }
            ?.let { return CaptureTarget.Existing(it) }
        return when (target) {
            LibraryVaultCaptureTarget.CreateNew -> CaptureTarget.CreateNew(
                mangaIdentity = UUID.randomUUID().toString(),
                manifestPath = "manga/${UUID.randomUUID()}.json",
            )
            is LibraryVaultCaptureTarget.Existing -> CaptureTarget.Existing(target.manga)
            is LibraryVaultCaptureTarget.Choose -> null
        }
    }

    private fun Manga.toCaptureManga(source: HttpSource): LibraryVaultCaptureManga {
        return LibraryVaultCaptureManga(
            mangaId = id,
            sourceId = source.id,
            sourceIdentity = "${source.id}:$url",
            title = title,
            metadata = VaultMetadata(
                title = title,
                author = author,
                artist = artist,
                description = description,
                status = status.toInt().toVaultStatus(),
            ),
        )
    }

    private fun LibraryVaultCaptureManga.withCreateNewTitle(
        createNew: Boolean,
        title: String?,
    ): LibraryVaultCaptureManga {
        val targetTitle = title?.trim()?.takeIf { createNew && it.isNotBlank() } ?: return this
        return copy(
            title = targetTitle,
            metadata = metadata.copy(title = targetTitle),
        )
    }

    private fun Chapter.toCaptureChapter(): LibraryVaultCaptureChapter {
        return LibraryVaultCaptureChapter(
            selectionId = url,
            title = name,
            chapterNumber = chapterNumber,
            volumeNumber = null,
            scanlator = scanlator,
            sourceOrder = sourceOrder,
            dateUpload = dateUpload,
            sourceChapterUrl = url,
        )
    }

    private fun VaultMetadata.toManifestMetadata() = VaultManifestMetadata(
        title = title,
        author = author,
        artist = artist,
        description = description,
        status = status,
    )

    private fun Int.toVaultStatus(): VaultMangaStatus {
        return when (this) {
            SManga.ONGOING -> VaultMangaStatus.ONGOING
            SManga.COMPLETED -> VaultMangaStatus.COMPLETED
            SManga.LICENSED -> VaultMangaStatus.LICENSED
            SManga.PUBLISHING_FINISHED -> VaultMangaStatus.PUBLISHING_FINISHED
            SManga.CANCELLED -> VaultMangaStatus.CANCELLED
            SManga.ON_HIATUS -> VaultMangaStatus.ON_HIATUS
            else -> VaultMangaStatus.UNKNOWN
        }
    }

    private fun orderCapturedChapters(
        chapters: List<VaultManifestChapter>,
        replacementIdentities: Set<String>,
    ): List<VaultManifestChapter> = orderLibraryVaultCaptureChapters(chapters, replacementIdentities)

    private fun decodeRootManifest(body: String): VaultRootManifest? {
        return when (val result = codec.decodeRoot(body)) {
            is VaultManifestReadResult.Success -> result.manifest
            else -> null
        }
    }

    private fun decodeMangaManifest(body: String): VaultMangaManifest? {
        return when (val result = codec.decodeManga(body)) {
            is VaultManifestReadResult.Success -> result.manifest
            else -> null
        }
    }

    private fun captureStagingRoot(): File = File(context.cacheDir, "content-vault-capture")

    private inner class WebDavClient(
        private val config: WebDavVaultConfig,
    ) {
        suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(config.serverUrl.resolveWebDavPath(path))
                .header("Authorization", Credentials.basic(config.username.trim(), config.password))
                .get()
                .build()
            client.newCall(request).await().use { response ->
                response.takeIf { it.isSuccessful }?.body?.string()
            }
        }

        suspend fun put(path: String, body: String): Boolean = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(config.serverUrl.resolveWebDavPath(path))
                .header("Authorization", Credentials.basic(config.username.trim(), config.password))
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).await().use { it.isSuccessful }
        }

        suspend fun putFile(path: String, file: UniFile): Boolean = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(config.serverUrl.resolveWebDavPath(path))
                .header("Authorization", Credentials.basic(config.username.trim(), config.password))
                .put(file.asRequestBody())
                .build()
            client.newCall(request).await().use { it.isSuccessful }
        }

        suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean = withContext(
            Dispatchers.IO,
        ) {
            val request = Request.Builder()
                .url(config.serverUrl.resolveWebDavPath(path))
                .header("Authorization", Credentials.basic(config.username.trim(), config.password))
                .put(bytes.toRequestBody(mediaType?.toMediaTypeOrNull()))
                .build()
            client.newCall(request).await().use { it.isSuccessful }
        }

        suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(config.serverUrl.resolveWebDavPath(path, collection = true))
                .header("Authorization", Credentials.basic(config.username.trim(), config.password))
                .method("MKCOL", EMPTY_BODY)
                .build()
            client.newCall(request).await().use { response ->
                response.isSuccessful || response.code == HTTP_METHOD_NOT_ALLOWED
            }
        }

        suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(config.serverUrl.resolveWebDavPath(path))
                .header("Authorization", Credentials.basic(config.username.trim(), config.password))
                .delete()
                .build()
            client.newCall(request).await().use { response ->
                response.isSuccessful || response.code == HTTP_NOT_FOUND
            }
        }
    }

    private sealed interface CaptureTarget {
        data class Existing(val manga: VaultManga) : CaptureTarget
        data class CreateNew(
            val mangaIdentity: String,
            val manifestPath: String,
        ) : CaptureTarget
    }

    private data class StagedChapter(
        val file: UniFile,
        val sizeBytes: Long,
        val checksumSha256: String,
    )

    private data class PublishedChapter(val replaced: Boolean)

    private data class ArchivedPage(
        val bytes: ByteArray,
        val extension: String,
    )

    private data class ChapterFailure(
        val title: String,
        val category: String,
    )

    private data class FileDigest(
        val sizeBytes: Long,
        val sha256: String,
    )

    private fun UniFile.digest(): FileDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        openInputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return FileDigest(size, digest.digest().toHex())
    }

    private fun UniFile.asRequestBody(): RequestBody {
        return object : RequestBody() {
            override fun contentType() = OCTET_MEDIA_TYPE

            override fun contentLength(): Long = length().takeIf { it >= 0 } ?: -1

            override fun writeTo(sink: BufferedSink) {
                openInputStream().use { input ->
                    sink.writeAll(input.source())
                }
            }
        }
    }

    private fun validateCbz(file: UniFile, expectedEntries: List<String>) {
        require(file.length() > 0) { "CBZ is empty" }
        val entries = ZipInputStream(file.openInputStream()).use { zip ->
            generateSequence { zip.nextEntry }
                .map { it.name }
                .toList()
        }
        require(entries == expectedEntries) { "CBZ entries did not validate" }
    }

    private fun Throwable.captureFailureCategory(): String {
        return message?.takeIf {
            it in setOf(
                "downloaded_copy",
                "empty_pages",
                "staging",
                "upload",
                "publish",
                "manifest",
                "target",
                "identity",
                "unconfirmed_duplicate",
            )
        } ?: "capture_failed"
    }

    private fun List<ChapterFailure>.toDetailJson(): String? {
        if (isEmpty()) return null
        return joinToString(prefix = "[", postfix = "]") {
            """{"title":${it.title.jsonString()},"category":${it.category.jsonString()}}"""
        }
    }

    private fun String.jsonString(): String {
        return buildString {
            append('"')
            this@jsonString.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_NOT_FOUND = 404
    }
}

sealed interface LibraryVaultCaptureResult {
    data class Captured(
        val addedChapterCount: Int,
        val replacedChapterCount: Int,
        val failedChapterCount: Int,
    ) : LibraryVaultCaptureResult

    data object IncompleteConfiguration : LibraryVaultCaptureResult
    data object NotLibraryManga : LibraryVaultCaptureResult
    data object SourceUnavailable : LibraryVaultCaptureResult
    data object NothingSelected : LibraryVaultCaptureResult
    data class TargetChoiceRequired(val plan: LibraryVaultCapturePlan) : LibraryVaultCaptureResult
}

internal fun orderLibraryVaultCaptureChapters(
    chapters: List<VaultManifestChapter>,
    replacementIdentities: Set<String>,
): List<VaultManifestChapter> {
    val replacementsByIdentity = chapters
        .filter { it.identity in replacementIdentities }
        .associateBy { it.identity }
    val newOrdered = chapters
        .filterNot { it.identity in replacementIdentities }
        .sortedWith { first, second ->
            second.title
                .duplicateTitleKey()
                .compareToCaseInsensitiveNaturalOrder(first.title.duplicateTitleKey())
        }
    val merged = newOrdered.toMutableList()
    chapters.forEachIndexed { index, original ->
        val replacement = replacementsByIdentity[original.identity] ?: return@forEachIndexed
        merged.add(index.coerceAtMost(merged.size), replacement)
    }
    return merged.mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }
}
