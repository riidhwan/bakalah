package eu.kanade.tachiyomi.data.vault

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.vault.interactor.BuildLocalVaultImportPlan
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LocalVaultImportChapter
import tachiyomi.domain.vault.model.LocalVaultImportManga
import tachiyomi.domain.vault.model.LocalVaultImportPlan
import tachiyomi.domain.vault.model.LocalVaultImportTarget
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
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestCover
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultManifestProvenance
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultRootManifest
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LocalVaultImportService(
    networkHelper: NetworkHelper,
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val sourceManager: SourceManager,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    private val refreshService: VaultCatalogueRefreshService,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {
    private val client = networkHelper.nonCloudflareClient
    private val codec = VaultManifestCodec(json)
    private val planner = BuildLocalVaultImportPlan()

    suspend fun preview(
        localManga: Manga,
        targetMangaId: Long? = null,
        createNew: Boolean = false,
    ): LocalVaultImportPreviewResult {
        val vault = configuredVault() ?: return LocalVaultImportPreviewResult.IncompleteConfiguration
        val scan = scanLocalManga(localManga) ?: return LocalVaultImportPreviewResult.LocalMangaNotFound
        val vaultManga = repository.getManga(vault.id)
        val existingChapters = existingChaptersByMangaId(vault.id)
        val suggestedPlan = planner.build(
            localManga = scan.manga,
            localChapters = scan.chapters.map { it.chapter },
            vaultManga = vaultManga,
            existingChaptersByMangaId = existingChapters,
            hint = repository.getImportTargetHint(localManga.id),
        )
        val plan = when {
            createNew -> planner.buildForTarget(
                target = LocalVaultImportTarget.CreateNew,
                localChapters = scan.chapters.map { it.chapter },
                existingChapters = emptyList(),
            )
            targetMangaId != null -> {
                val target = vaultManga.firstOrNull { it.id == targetMangaId }
                    ?: return LocalVaultImportPreviewResult.Success(
                        plan = suggestedPlan,
                        availableTargets = vaultManga,
                    )
                planner.buildForTarget(
                    target = LocalVaultImportTarget.Existing(target, LocalVaultImportTarget.Reason.USER_SELECTED),
                    localChapters = scan.chapters.map { it.chapter },
                    existingChapters = existingChapters[target.id].orEmpty(),
                )
            }
            else -> suggestedPlan
        }
        return LocalVaultImportPreviewResult.Success(
            plan = plan,
            availableTargets = vaultManga,
        )
    }

    suspend fun import(
        localManga: Manga,
        selectedChapterIds: Set<String>? = null,
        targetMangaId: Long? = null,
        createNew: Boolean = false,
        createNewTitle: String? = null,
        progress: (LocalVaultImportProgress) -> Unit = {},
    ): LocalVaultImportResult {
        val config = preferences.getWebDavConfig()
        val vault = configuredVault() ?: return LocalVaultImportResult.IncompleteConfiguration
        val expectedVaultIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        val scan = scanLocalManga(
            manga = localManga,
            selectedChapterIds = selectedChapterIds,
        )
            ?.withCreateNewTitle(createNew = createNew, title = createNewTitle)
            ?: return LocalVaultImportResult.LocalMangaNotFound
        val vaultManga = repository.getManga(vault.id)
        val existingChapters = existingChaptersByMangaId(vault.id)
        val plan = planner.build(
            localManga = scan.manga,
            localChapters = scan.chapters.map { it.chapter },
            vaultManga = vaultManga,
            existingChaptersByMangaId = existingChapters,
            hint = repository.getImportTargetHint(localManga.id),
        )
        val target = resolveTarget(plan.target, vaultManga, targetMangaId, createNew)
            ?: return LocalVaultImportResult.TargetChoiceRequired(plan)

        val selectedIds = selectedChapterIds ?: plan.chapters
            .filter { it.selectedByDefault }
            .map { it.chapter.selectionId }
            .toSet()
        val selectedChapters = scan.chapters.filter { it.chapter.selectionId in selectedIds }
        if (selectedChapters.isEmpty()) return LocalVaultImportResult.NothingSelected(plan)
        val progressTotal = selectedChapters.size.coerceAtLeast(1)
        var progressCurrent = 0
        fun updateProgress(chapter: ScannedLocalChapter) {
            progress(
                LocalVaultImportProgress(
                    current = progressCurrent.coerceAtMost(progressTotal),
                    total = progressTotal,
                    chapterTitle = chapter.chapter.title,
                ),
            )
        }

        val webDav = WebDavClient(config)
        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootManifest =
            readRootManifest(webDav, rootPath) ?: return LocalVaultImportResult.ManifestUnavailable(rootPath)
        if (expectedVaultIdentity != null && rootManifest.identity != expectedVaultIdentity) {
            return LocalVaultImportResult.IdentityChanged(ContentVaultIdentity(rootManifest.identity))
        }

        val now = System.currentTimeMillis()
        val mangaManifestPath = when (target) {
            is ImportTarget.Existing ->
                rootManifest.manga
                    .firstOrNull { it.identity == target.manga.identity.value }
                    ?.path
                    ?: "manga/${target.manga.identity.value}.json"
            ImportTarget.CreateNew -> "manga/${UUID.randomUUID()}.json"
        }
        val remoteMangaManifest = when (target) {
            is ImportTarget.Existing -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
            ImportTarget.CreateNew -> null
        }
        val mangaIdentity = target.mangaIdentity(remoteMangaManifest)
        val existingRemoteChapters = remoteMangaManifest?.chapters.orEmpty()

        webDav.createDirectory(config.rootPath.childPath("manga"))
        webDav.createDirectory(config.rootPath.childPath("content"))
        val existingRemoteChaptersByFileKey = existingRemoteChapters
            .associateBy { it.content.path.substringAfterLast('/').duplicateFileKey() }
        val replacedChapterIdentities = mutableSetOf<String>()
        val replacedRemoteContentPaths = mutableSetOf<String>()
        val importedChapters = runCatching {
            selectedChapters.map { localChapter ->
                updateProgress(localChapter)
                val preparedChapter = localChapter.convertDirectoryToCbzIfNeeded()
                val replacement = existingRemoteChaptersByFileKey[
                    localChapter.chapter.sourceFileName.duplicateFileKey(),
                ]
                val chapterIdentity = replacement?.identity ?: UUID.randomUUID().toString()
                val contentIdentity = if (replacement == null) chapterIdentity else UUID.randomUUID().toString()
                val contentPath = uploadChapter(
                    webDav = webDav,
                    config = config,
                    mangaIdentity = mangaIdentity,
                    contentIdentity = contentIdentity,
                    localChapter = preparedChapter,
                )
                if (replacement != null) {
                    replacedChapterIdentities += replacement.identity
                    replacedRemoteContentPaths += replacement.content.path
                    preparedChapter.toReplacementManifestChapter(
                        existing = replacement,
                        contentPath = contentPath,
                        now = now,
                    )
                } else {
                    preparedChapter.toManifestChapter(
                        identity = chapterIdentity,
                        contentPath = contentPath,
                        now = now,
                    )
                }
                    .also {
                        progressCurrent += 1
                        updateProgress(preparedChapter)
                    }
            }
        }.getOrElse {
            return LocalVaultImportResult.UploadFailed
        }
        val importedCover = remoteMangaManifest?.cover ?: runCatching {
            uploadCover(
                webDav = webDav,
                config = config,
                mangaIdentity = mangaIdentity,
                coverFile = scan.coverFile,
                now = now,
            )
        }.getOrElse {
            return LocalVaultImportResult.UploadFailed
        }

        val metadata = target.metadata(scan.manga)
        val mangaRevision = remoteMangaManifest?.revisionNumber?.plus(1) ?: 1
        val mangaRevisionId = UUID.randomUUID().toString()
        val mangaManifest = VaultMangaManifest(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            vaultIdentity = rootManifest.identity,
            mangaIdentity = mangaIdentity,
            revisionId = mangaRevisionId,
            revisionNumber = mangaRevision,
            metadata = metadata.toManifestMetadata(),
            labels = remoteMangaManifest?.labels.orEmpty(),
            cover = importedCover,
            chapters = orderVaultImportChapters(
                chapters = existingRemoteChapters.filterNot {
                    it.identity in replacedChapterIdentities
                } + importedChapters,
                replacementIdentities = replacedChapterIdentities,
            ),
            provenance = VaultManifestProvenance(
                importedFrom = "local",
                sourceName = localSource()?.name,
                sourceUri = scan.manga.localMangaIdentity,
                importedAt = now,
            ),
            createdAt = remoteMangaManifest?.createdAt ?: now,
            updatedAt = now,
        )

        if (!webDav.put(config.rootPath.childPath(mangaManifestPath), codec.encodeManga(mangaManifest))) {
            return LocalVaultImportResult.UploadFailed
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
        val rootRevision = rootManifest.revisionNumber + 1
        val oldTargetChapterCount = remoteMangaManifest?.chapters?.size ?: 0
        val updatedRoot = rootManifest.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = rootRevision,
            updatedAt = now,
            summary = VaultCatalogueSummary(
                mangaCount = updatedPointers.size.toLong(),
                chapterCount = rootManifest.summary.chapterCount - oldTargetChapterCount + mangaManifest.chapters.size,
                labelCount = rootManifest.summary.labelCount,
                updatedAt = now,
            ),
            manga = updatedPointers,
        )
        if (!webDav.put(rootPath, codec.encodeRoot(updatedRoot))) return LocalVaultImportResult.UploadFailed

        cleanupReplacedRemoteContent(webDav, config, replacedRemoteContentPaths)
        invalidateReplacementCacheState(target, replacedChapterIdentities)
        repository.upsertImportTargetHint(
            refreshLocalIndex(vault.identity, mangaIdentity)
                .takeIf { it != -1L }
                ?.let { vaultMangaId ->
                    ImportTargetHint(
                        localMangaId = localManga.id,
                        localMangaIdentity = scan.manga.localMangaIdentity,
                        contentVaultIdentity = vault.identity,
                        sourceIdentity = scan.manga.localMangaIdentity,
                        vaultMangaIdentity = VaultIdentity(mangaIdentity),
                        vaultMangaId = vaultMangaId,
                        updatedAt = now,
                    )
                }
                ?: return LocalVaultImportResult.Imported(
                    mangaIdentity = VaultIdentity(mangaIdentity),
                    importedChapterCount = importedChapters.size,
                ),
        )

        return LocalVaultImportResult.Imported(
            mangaIdentity = VaultIdentity(mangaIdentity),
            importedChapterCount = importedChapters.size,
        )
    }

    private suspend fun configuredVault() =
        preferences.configuredVaultIdentity.get()
            .takeIf { it.isNotBlank() }
            ?.let { repository.getVaultByIdentity(ContentVaultIdentity(it)) }

    private suspend fun existingChaptersByMangaId(vaultId: Long): Map<Long, List<VaultChapter>> {
        return repository.getChaptersForVault(vaultId).groupBy { it.mangaId }
    }

    private suspend fun scanLocalManga(
        manga: Manga,
        selectedChapterIds: Set<String>? = null,
    ): LocalMangaScan? = withContext(Dispatchers.IO) {
        if (selectedChapterIds != null) {
            return@withContext scanSelectedLocalManga(manga, selectedChapterIds)
        }

        val localSource = localSource() ?: return@withContext null
        val details = manga.toSourceManga()
        val chapterFiles = fileSystem.getFilesInMangaDirectory(manga.url).associateBy { it.name.orEmpty() }
        val chapters = localSource.getChapterList(details).mapIndexedNotNull { index, chapter ->
            val fileName = chapter.url.substringAfter('/', missingDelimiterValue = chapter.url)
            val file = chapterFiles[fileName] ?: return@mapIndexedNotNull null
            if (!file.isDirectory && !file.isCbz()) return@mapIndexedNotNull null
            val digest = file.previewDigest()
            ScannedLocalChapter(
                file = file,
                chapter = LocalVaultImportChapter(
                    selectionId = chapter.url,
                    sourceFileName = file.name.orEmpty(),
                    title = chapter.name,
                    chapterNumber = chapter.chapter_number.toDouble(),
                    volumeNumber = null,
                    scanlator = chapter.scanlator,
                    sourceOrder = index.toLong(),
                    contentFormat = VaultChapterContentFormat.CBZ,
                    sizeBytes = digest.sizeBytes,
                    checksumSha256 = digest.sha256,
                    dateUpload = chapter.date_upload,
                    requiresLocalCbzConversion = file.isDirectory,
                ),
            )
        }
        LocalMangaScan(
            manga = LocalVaultImportManga(
                localMangaId = manga.id,
                localMangaIdentity = manga.url,
                title = details.title,
                metadata = VaultMetadata(
                    title = details.title,
                    author = details.author,
                    artist = details.artist,
                    description = details.description,
                    status = details.status.toVaultStatus(),
                ),
            ),
            chapters = chapters,
            coverFile = coverManager.find(manga.url),
        )
    }

    private suspend fun scanSelectedLocalManga(
        manga: Manga,
        selectedChapterIds: Set<String>,
    ): LocalMangaScan? {
        val details = manga.toSourceManga()
        val chapterFiles = fileSystem.getFilesInMangaDirectory(manga.url).associateBy { it.name.orEmpty() }
        val chapters = getChaptersByMangaId.await(manga.id)
            .filter { it.url in selectedChapterIds }
            .mapNotNull { chapter ->
                val fileName = chapter.url.substringAfter('/', missingDelimiterValue = chapter.url)
                val file = chapterFiles[fileName] ?: return@mapNotNull null
                if (!file.isDirectory && !file.isCbz()) return@mapNotNull null
                val digest = file.previewDigest()
                ScannedLocalChapter(
                    file = file,
                    chapter = chapter.toLocalVaultImportChapter(
                        sourceFileName = file.name.orEmpty(),
                        sizeBytes = digest.sizeBytes,
                        checksumSha256 = digest.sha256,
                        requiresLocalCbzConversion = file.isDirectory,
                    ),
                )
            }
        return LocalMangaScan(
            manga = details.toLocalVaultImportManga(manga),
            chapters = chapters,
            coverFile = coverManager.find(manga.url),
        )
    }

    private fun localSource(): LocalSource? = sourceManager.get(LocalSource.ID) as? LocalSource

    private fun ScannedLocalChapter.convertDirectoryToCbzIfNeeded(): ScannedLocalChapter {
        return if (file.isDirectory) convertDirectoryToCbz() else this
    }

    private fun ScannedLocalChapter.convertDirectoryToCbz(): ScannedLocalChapter {
        val parent = file.parentFile ?: error("Local chapter directory has no parent")
        val finalName = collisionSafeCbzName(
            baseName = directoryChapterCbzBaseName(file.name),
            existingNames = parent.listFiles().orEmpty().mapNotNull { it.name }.toSet(),
        )
        val tempName = ".$finalName.tmp-${UUID.randomUUID()}.cbz"
        val tempFile = parent.createFile(tempName) ?: error("Could not create temporary CBZ")
        try {
            val entries = file.listReadablePageEntries()
            require(entries.isNotEmpty()) { "Directory chapter has no readable image pages" }
            tempFile.openOutputStream().use { output ->
                writeStoredCbz(
                    output = output,
                    entries = entries.map { entry ->
                        CbzEntry(
                            name = entry.relativePath,
                            openInputStream = { entry.file.openInputStream() },
                        )
                    },
                )
            }
            validateCbz(tempFile, entries.map { it.relativePath })
            if (!tempFile.renameTo(finalName)) {
                error("Could not promote temporary CBZ")
            }
            val archive = parent.findFile(finalName) ?: error("Promoted CBZ is missing")
            val digest = archive.digest()
            file.deleteRecursively()
            return copy(
                file = archive,
                chapter = chapter.copy(
                    sourceFileName = archive.name.orEmpty(),
                    contentFormat = VaultChapterContentFormat.CBZ,
                    sizeBytes = digest.sizeBytes,
                    checksumSha256 = digest.sha256,
                    requiresLocalCbzConversion = false,
                ),
            )
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
    }

    private fun Manga.toSourceManga(): SManga {
        return SManga.create().apply {
            url = this@toSourceManga.url
            title = this@toSourceManga.title
            author = this@toSourceManga.author
            artist = this@toSourceManga.artist
            description = this@toSourceManga.description
            status = this@toSourceManga.status.toInt()
            thumbnail_url = this@toSourceManga.thumbnailUrl
        }
    }

    private fun SManga.toLocalVaultImportManga(manga: Manga): LocalVaultImportManga {
        return LocalVaultImportManga(
            localMangaId = manga.id,
            localMangaIdentity = manga.url,
            title = title,
            metadata = VaultMetadata(
                title = title,
                author = author,
                artist = artist,
                description = description,
                status = status.toVaultStatus(),
            ),
        )
    }

    private fun Chapter.toLocalVaultImportChapter(
        sourceFileName: String,
        sizeBytes: Long,
        checksumSha256: String,
        requiresLocalCbzConversion: Boolean,
    ): LocalVaultImportChapter {
        return LocalVaultImportChapter(
            selectionId = url,
            sourceFileName = sourceFileName,
            title = name,
            chapterNumber = chapterNumber,
            volumeNumber = null,
            scanlator = scanlator,
            sourceOrder = sourceOrder,
            contentFormat = VaultChapterContentFormat.CBZ,
            sizeBytes = sizeBytes,
            checksumSha256 = checksumSha256,
            dateUpload = dateUpload,
            requiresLocalCbzConversion = requiresLocalCbzConversion,
        )
    }

    private fun resolveTarget(
        target: LocalVaultImportTarget,
        vaultManga: List<VaultManga>,
        targetMangaId: Long?,
        createNew: Boolean,
    ): ImportTarget? {
        if (createNew) return ImportTarget.CreateNew
        targetMangaId
            ?.let { id -> vaultManga.firstOrNull { it.id == id } }
            ?.let { return ImportTarget.Existing(it, LocalVaultImportTarget.Reason.USER_SELECTED) }
        return when (target) {
            LocalVaultImportTarget.CreateNew -> ImportTarget.CreateNew
            is LocalVaultImportTarget.Existing -> ImportTarget.Existing(target.manga, target.reason)
            is LocalVaultImportTarget.Choose -> null
        }
    }

    private suspend fun uploadChapter(
        webDav: WebDavClient,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        contentIdentity: String,
        localChapter: ScannedLocalChapter,
    ): String {
        val basePath = "content/$mangaIdentity/$contentIdentity"
        val remoteBasePath = config.rootPath.childPath(basePath)
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        return if (localChapter.file.isDirectory) {
            webDav.createDirectory(remoteBasePath)
            localChapter.file.listFilesRecursively().forEach { file ->
                val relativePath = file.relativePathFrom(localChapter.file)
                val path = config.rootPath.childPath("$basePath/$relativePath")
                webDav.createParentDirectories(path)
                if (!webDav.putFile(path, file)) {
                    error("Failed to upload $path")
                }
            }
            basePath
        } else {
            webDav.createDirectory(remoteBasePath)
            val extension = localChapter.file.extension?.let { ".$it" }.orEmpty()
            val path = "$basePath/${localChapter.file.nameWithoutExtension}$extension"
            if (!webDav.putFile(config.rootPath.childPath(path), localChapter.file)) {
                error("Failed to upload $path")
            }
            path
        }
    }

    private suspend fun uploadCover(
        webDav: WebDavClient,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        coverFile: UniFile?,
        now: Long,
    ): VaultManifestCover? {
        coverFile ?: return null
        val coverIdentity = UUID.randomUUID().toString()
        val extension = coverFile.extension
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?: "img"
        val path = "content/$mangaIdentity/cover/$coverIdentity.$extension"
        val digest = coverFile.digest()

        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        webDav.createDirectory(config.rootPath.childPath("content/$mangaIdentity/cover"))
        if (!webDav.putFile(config.rootPath.childPath(path), coverFile)) {
            error("Failed to upload $path")
        }

        return VaultManifestCover(
            identity = coverIdentity,
            path = path,
            mediaType = coverFile.coverMediaType(),
            integrity = VaultContentIntegrity(
                sizeBytes = digest.sizeBytes,
                checksumSha256 = digest.sha256,
            ),
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = 1,
            updatedAt = now,
        )
    }

    private suspend fun readRootManifest(webDav: WebDavClient, path: String): VaultRootManifest? {
        return webDav.get(path)?.let { body ->
            when (val result = codec.decodeRoot(body)) {
                is VaultManifestReadResult.Success -> result.manifest
                else -> null
            }
        }
    }

    private fun decodeMangaManifest(body: String): VaultMangaManifest? {
        return when (val result = codec.decodeManga(body)) {
            is VaultManifestReadResult.Success -> result.manifest
            else -> null
        }
    }

    private suspend fun refreshLocalIndex(vaultIdentity: ContentVaultIdentity, mangaIdentity: String): Long {
        refreshService.refreshConfiguredVault()
        return repository.getVaultByIdentity(vaultIdentity)
            ?.let { repository.getManga(it.id) }
            ?.firstOrNull { it.identity.value == mangaIdentity }
            ?.id
            ?: -1
    }

    private suspend fun cleanupReplacedRemoteContent(
        webDav: WebDavClient,
        config: WebDavVaultConfig,
        paths: Set<String>,
    ) {
        paths.forEach { path ->
            runCatching { webDav.delete(config.rootPath.childPath(path)) }
        }
    }

    private suspend fun invalidateReplacementCacheState(
        target: ImportTarget,
        replacedChapterIdentities: Set<String>,
    ) {
        if (target !is ImportTarget.Existing || replacedChapterIdentities.isEmpty()) return
        val replacedChapterIds = repository.getChapters(target.manga.id)
            .filter { it.identity.value in replacedChapterIdentities }
            .map { it.id }
        repository.deleteCacheStates(replacedChapterIds)
    }

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

        suspend fun createParentDirectories(path: String) {
            path.substringBeforeLast('/', missingDelimiterValue = "")
                .split('/')
                .runningFold("") { parent, child -> if (parent.isBlank()) child else "$parent/$child" }
                .drop(1)
                .forEach { createDirectory(it) }
        }
    }

    private sealed interface ImportTarget {
        data class Existing(
            val manga: VaultManga,
            val reason: LocalVaultImportTarget.Reason,
        ) : ImportTarget

        data object CreateNew : ImportTarget
    }

    private fun ImportTarget.mangaIdentity(remoteManifest: VaultMangaManifest?): String {
        return when (this) {
            is ImportTarget.Existing -> manga.identity.value
            ImportTarget.CreateNew -> remoteManifest?.mangaIdentity ?: UUID.randomUUID().toString()
        }
    }

    private fun ImportTarget.metadata(localManga: LocalVaultImportManga): VaultMetadata {
        return when (this) {
            is ImportTarget.Existing -> manga.metadata
            ImportTarget.CreateNew -> localManga.metadata
        }
    }

    private fun ScannedLocalChapter.toManifestChapter(
        identity: String,
        contentPath: String,
        now: Long,
    ) = VaultManifestChapter(
        identity = identity,
        title = chapter.title,
        chapterNumber = chapter.chapterNumber,
        volumeNumber = chapter.volumeNumber,
        scanlator = chapter.scanlator,
        sourceOrder = chapter.sourceOrder,
        content = VaultManifestChapterContent(
            path = contentPath,
            format = chapter.contentFormat,
            integrity = VaultContentIntegrity(
                sizeBytes = chapter.sizeBytes,
                checksumSha256 = chapter.checksumSha256,
            ),
        ),
        revisionId = UUID.randomUUID().toString(),
        revisionNumber = 1,
        dateUpload = chapter.dateUpload,
        createdAt = now,
        updatedAt = now,
    )

    private fun ScannedLocalChapter.toReplacementManifestChapter(
        existing: VaultManifestChapter,
        contentPath: String,
        now: Long,
    ) = existing.copy(
        content = VaultManifestChapterContent(
            path = contentPath,
            format = chapter.contentFormat,
            integrity = VaultContentIntegrity(
                sizeBytes = chapter.sizeBytes,
                checksumSha256 = chapter.checksumSha256,
            ),
        ),
        revisionId = UUID.randomUUID().toString(),
        revisionNumber = existing.revisionNumber + 1,
        updatedAt = now,
    )

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

    private fun UniFile.digest(): FileDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        if (isDirectory) {
            listFilesRecursively().forEach { file ->
                val relativePath = file.relativePathFrom(this)
                digest.update(relativePath.toByteArray())
                digest.update(0.toByte())
                size += file.updateDigest(digest)
            }
        } else {
            size = updateDigest(digest)
        }
        return FileDigest(size, digest.digest().toHex())
    }

    private fun UniFile.previewDigest(): FileDigest {
        if (!isDirectory) return digest()

        return FileDigest(
            sizeBytes = length().takeIf { it >= 0 } ?: 0,
            sha256 = "$PENDING_DIRECTORY_CBZ_CHECKSUM_PREFIX$uri",
        )
    }

    private fun UniFile.updateDigest(digest: MessageDigest): Long {
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
        return size
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

    private fun UniFile.listFilesRecursively(): List<UniFile> {
        return listFiles().orEmpty()
            .flatMap { file -> if (file.isDirectory) file.listFilesRecursively() else listOf(file) }
            .sortedWith { first, second ->
                first.relativePathFrom(this).compareToCaseInsensitiveNaturalOrder(second.relativePathFrom(this))
            }
    }

    private fun UniFile.listReadablePageEntries(): List<LocalPageEntry> {
        return listFiles().orEmpty()
            .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            .sortedWith { first, second ->
                first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
            }
            .map { LocalPageEntry(file = it, relativePath = cbzEntryName(it.name.orEmpty())) }
    }

    private fun UniFile.isCbz(): Boolean {
        return name.orEmpty().substringAfterLast('.', missingDelimiterValue = "").equals("cbz", ignoreCase = true) &&
            Format.valueOf(this) is Format.Archive
    }

    private fun UniFile.coverMediaType(): String? {
        return when (extension?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> null
        }
    }

    private fun UniFile.relativePathFrom(root: UniFile): String {
        return relativePathFromUriStrings(root.uri.toString(), uri.toString())
    }

    private fun UniFile.deleteRecursively() {
        if (isDirectory) {
            listFiles().orEmpty().forEach { it.deleteRecursively() }
        }
        delete()
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    private data class LocalMangaScan(
        val manga: LocalVaultImportManga,
        val chapters: List<ScannedLocalChapter>,
        val coverFile: UniFile?,
    )

    private fun LocalMangaScan.withCreateNewTitle(
        createNew: Boolean,
        title: String?,
    ): LocalMangaScan {
        val targetTitle = title?.trim()?.takeIf { createNew && it.isNotBlank() } ?: return this
        return copy(
            manga = manga.copy(
                title = targetTitle,
                metadata = manga.metadata.copy(title = targetTitle),
            ),
        )
    }

    private data class ScannedLocalChapter(
        val file: UniFile,
        val chapter: LocalVaultImportChapter,
    )

    private data class LocalPageEntry(
        val file: UniFile,
        val relativePath: String,
    )

    private data class FileDigest(
        val sizeBytes: Long,
        val sha256: String,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_NOT_FOUND = 404
        private const val PENDING_DIRECTORY_CBZ_CHECKSUM_PREFIX = "pending-directory-cbz:"
    }
}

internal data class CbzEntry(
    val name: String,
    val openInputStream: () -> InputStream,
)

internal fun collisionSafeCbzName(baseName: String, existingNames: Set<String>): String {
    val sanitized = baseName
        .trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim('.')
        .ifBlank { "chapter" }
    val base = sanitized.removeSuffix(".cbz")
    var candidate = "$base.cbz"
    var index = 1
    while (existingNames.any { it.equals(candidate, ignoreCase = true) }) {
        candidate = "$base ($index).cbz"
        index++
    }
    return candidate
}

internal fun directoryChapterCbzBaseName(directoryName: String?): String {
    return directoryName.orEmpty()
}

internal fun cbzEntryName(name: String): String {
    return name
        .trim()
        .replace('\\', '/')
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/")
}

internal fun writeStoredCbz(
    output: OutputStream,
    entries: List<CbzEntry>,
) {
    ZipOutputStream(output).use { zip ->
        entries.forEach { entry ->
            val bytes = entry.openInputStream().use { it.readBytes() }
            val crc = CRC32().apply { update(bytes) }
            val zipEntry = ZipEntry(cbzEntryName(entry.name)).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            }
            zip.putNextEntry(zipEntry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

internal fun orderVaultImportChapters(
    chapters: List<VaultManifestChapter>,
    replacementIdentities: Set<String>,
): List<VaultManifestChapter> {
    val reservedOrders = chapters
        .filter { it.identity in replacementIdentities }
        .map { it.sourceOrder }
        .toSet()
    var nextOrder = 0L
    fun nextAvailableOrder(): Long {
        while (nextOrder in reservedOrders) {
            nextOrder++
        }
        return nextOrder++
    }

    val replacements = chapters
        .filter { it.identity in replacementIdentities }
        .associateBy { it.identity }
    val orderedNonReplacements = chapters
        .filterNot { it.identity in replacementIdentities }
        .sortedWith { first, second ->
            second.importFileName().compareToCaseInsensitiveNaturalOrder(first.importFileName())
        }
        .map { it.copy(sourceOrder = nextAvailableOrder()) }
        .associateBy { it.identity }

    return chapters
        .map { chapter -> replacements[chapter.identity] ?: orderedNonReplacements.getValue(chapter.identity) }
        .sortedWith(compareBy<VaultManifestChapter> { it.sourceOrder }.thenBy { it.importFileName() })
}

sealed interface LocalVaultImportPreviewResult {
    data object IncompleteConfiguration : LocalVaultImportPreviewResult
    data object LocalMangaNotFound : LocalVaultImportPreviewResult
    data class Success(
        val plan: LocalVaultImportPlan,
        val availableTargets: List<VaultManga>,
    ) : LocalVaultImportPreviewResult
}

sealed interface LocalVaultImportResult {
    data object IncompleteConfiguration : LocalVaultImportResult
    data object LocalMangaNotFound : LocalVaultImportResult
    data class TargetChoiceRequired(val plan: LocalVaultImportPlan) : LocalVaultImportResult
    data class NothingSelected(val plan: LocalVaultImportPlan) : LocalVaultImportResult
    data class ManifestUnavailable(val path: String) : LocalVaultImportResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : LocalVaultImportResult
    data object UploadFailed : LocalVaultImportResult
    data class Imported(
        val mangaIdentity: VaultIdentity,
        val importedChapterCount: Int,
    ) : LocalVaultImportResult
}

internal fun String.resolveWebDavPath(path: String, collection: Boolean = false): HttpUrl {
    val builder = trim().trimEnd('/').toHttpUrl().newBuilder()
    path.trim().trim('/')
        .split('/')
        .filter { it.isNotBlank() }
        .forEach { builder.addPathSegment(it) }
    if (collection) {
        builder.addPathSegment("")
    }
    return builder.build()
}

internal fun relativePathFromUriStrings(rootUri: String, fileUri: String): String {
    return fileUri
        .decodePercentEscapes()
        .removePrefix(rootUri.decodePercentEscapes().trimEnd('/', '\\'))
        .trimStart('/', '\\')
}

private fun String.decodePercentEscapes(): String {
    val result = StringBuilder(length)
    val bytes = ByteArrayOutputStream()

    fun flushBytes() {
        if (bytes.size() > 0) {
            result.append(bytes.toByteArray().toString(StandardCharsets.UTF_8))
            bytes.reset()
        }
    }

    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val value = substring(index + 1, index + 3).toIntOrNull(16)
            if (value != null) {
                bytes.write(value)
                index += 3
                continue
            }
        }
        flushBytes()
        result.append(char)
        index++
    }
    flushBytes()
    return result.toString()
}

private fun String.duplicateFileKey(): String {
    val trimmed = trim()
    return trimmed
        .substringBeforeLast('.', missingDelimiterValue = trimmed)
        .lowercase()
}

private fun VaultManifestChapter.importFileName(): String {
    return content.path.substringAfterLast('/')
}
