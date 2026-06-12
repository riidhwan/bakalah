package eu.kanade.tachiyomi.data.vault

import android.app.Application
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.importing.LocalVaultChapterStager
import eu.kanade.tachiyomi.data.vault.importing.LocalVaultImportChapterFailure
import eu.kanade.tachiyomi.data.vault.importing.LocalVaultMangaScanner
import eu.kanade.tachiyomi.data.vault.importing.ScannedLocalVaultChapter
import eu.kanade.tachiyomi.data.vault.importing.asRequestBody
import eu.kanade.tachiyomi.data.vault.importing.childPath
import eu.kanade.tachiyomi.data.vault.importing.coverMediaType
import eu.kanade.tachiyomi.data.vault.importing.deleteRecursively
import eu.kanade.tachiyomi.data.vault.importing.digest
import eu.kanade.tachiyomi.data.vault.importing.duplicateFileKey
import eu.kanade.tachiyomi.data.vault.importing.listFilesRecursively
import eu.kanade.tachiyomi.data.vault.importing.localImportFailureCategory
import eu.kanade.tachiyomi.data.vault.importing.orderVaultImportChapters
import eu.kanade.tachiyomi.data.vault.importing.relativePathFrom
import eu.kanade.tachiyomi.data.vault.importing.resolveWebDavPath
import eu.kanade.tachiyomi.data.vault.importing.toDetailJson
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.vault.interactor.BuildLocalVaultImportPlan
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
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
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.File
import java.util.UUID

class LocalVaultImportService internal constructor(
    private val app: Application,
    networkHelper: NetworkHelper,
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val refreshService: VaultCatalogueRefreshService,
    private val scanner: LocalVaultMangaScanner,
    private val chapterStager: LocalVaultChapterStager,
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
        val scan = scanner.scan(localManga) ?: return LocalVaultImportPreviewResult.LocalMangaNotFound
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
        allowedReplacementChapterIds: Set<String> = emptySet(),
        targetMangaId: Long? = null,
        createNew: Boolean = false,
        createNewTitle: String? = null,
        progress: (LocalVaultImportProgress) -> Unit = {},
    ): LocalVaultImportResult {
        val config = preferences.getWebDavConfig()
        val vault = configuredVault() ?: return LocalVaultImportResult.IncompleteConfiguration
        val expectedVaultIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        val scan = scanner.scan(
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

        val webDav = WebDavClient(config)
        val now = System.currentTimeMillis()
        var job = VaultTransferJob(
            id = -1,
            vaultId = vault.id,
            chapterId = null,
            type = VaultTransferType.IMPORT_PUBLISH,
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

        var activeTarget = target.toActiveImportTarget()
        var added = 0
        var replaced = 0
        val failures = (selectedIds - selectedChapters.map { it.chapter.selectionId }.toSet())
            .map { LocalVaultImportChapterFailure(it, "missing_chapter") }
            .toMutableList()
        val stagingRoot = importStagingRoot().apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            selectedChapters.forEachIndexed { index, localChapter ->
                currentCoroutineContext().ensureActive()
                fun updatePhase(phase: VaultImportProgressPhase? = null, indeterminate: Boolean = false) {
                    progress(
                        LocalVaultImportProgress(
                            current = index,
                            total = progressTotal,
                            chapterTitle = localChapter.chapter.title,
                            indeterminate = indeterminate,
                            phase = phase,
                        ),
                    )
                }
                updatePhase(VaultImportProgressPhase.PREPARING, indeterminate = true)
                val chapterStagingRoot = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
                val published = runCatching {
                    try {
                        publishChapter(
                            webDav = webDav,
                            config = config,
                            vaultIdentity = vault.identity,
                            expectedVaultIdentity = expectedVaultIdentity,
                            localManga = localManga,
                            importManga = scan.manga,
                            localChapter = localChapter,
                            coverFile = scan.coverFile,
                            target = activeTarget,
                            allowReplacement = localChapter.chapter.selectionId in allowedReplacementChapterIds,
                            stagingRoot = chapterStagingRoot,
                            progressPhase = { phase -> updatePhase(phase, indeterminate = true) },
                        )
                    } finally {
                        chapterStagingRoot.deleteRecursively()
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    if (error is LocalImportGlobalFailure) throw error
                    failures +=
                        LocalVaultImportChapterFailure(localChapter.chapter.title, error.localImportFailureCategory())
                    return@forEachIndexed
                }
                activeTarget = published.target
                if (published.replaced) {
                    replaced += 1
                } else {
                    added += 1
                }
                progress(
                    LocalVaultImportProgress(
                        current = index + 1,
                        total = progressTotal,
                        chapterTitle = localChapter.chapter.title,
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
        } catch (e: LocalImportGlobalFailure) {
            val cancelled = selectedChapters.size - added - replaced - failures.size - 1
            failures += LocalVaultImportChapterFailure(e.chapterTitle ?: scan.manga.title, e.category)
            val completedAt = System.currentTimeMillis()
            repository.upsertTransferJob(
                job.copy(
                    state = if (added + replaced > 0) {
                        VaultTransferState.PARTIALLY_SUCCEEDED
                    } else {
                        VaultTransferState.FAILED
                    },
                    failureReason = e.category,
                    addedCount = added.toLong(),
                    replacedCount = replaced.toLong(),
                    failedCount = failures.size.toLong(),
                    cancelledCount = cancelled.coerceAtLeast(0).toLong(),
                    detailJson = failures.toDetailJson(),
                    updatedAt = completedAt,
                    completedAt = completedAt,
                ),
            )
            return if (added + replaced > 0) {
                LocalVaultImportResult.Imported(
                    mangaIdentity = activeTarget.mangaIdentity?.let(::VaultIdentity) ?: VaultIdentity(""),
                    addedChapterCount = added,
                    replacedChapterCount = replaced,
                    failedChapterCount = failures.size,
                )
            } else {
                LocalVaultImportResult.UploadFailed
            }
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

        if (added + replaced == 0) return LocalVaultImportResult.UploadFailed

        return activeTarget.mangaIdentity?.let { mangaIdentity ->
            LocalVaultImportResult.Imported(
                mangaIdentity = VaultIdentity(mangaIdentity),
                addedChapterCount = added,
                replacedChapterCount = replaced,
                failedChapterCount = failures.size,
            )
        } ?: LocalVaultImportResult.UploadFailed
    }

    private suspend fun publishChapter(
        webDav: WebDavClient,
        config: WebDavVaultConfig,
        vaultIdentity: ContentVaultIdentity,
        expectedVaultIdentity: String?,
        localManga: Manga,
        importManga: LocalVaultImportManga,
        localChapter: ScannedLocalVaultChapter,
        coverFile: UniFile?,
        target: ActiveImportTarget,
        allowReplacement: Boolean,
        stagingRoot: File,
        progressPhase: (VaultImportProgressPhase) -> Unit,
    ): PublishedLocalChapter {
        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootManifest = readRootManifest(webDav, rootPath)
            ?: throw LocalImportGlobalFailure("manifest", localChapter.chapter.title)
        if (expectedVaultIdentity != null && rootManifest.identity != expectedVaultIdentity) {
            throw LocalImportGlobalFailure("identity", localChapter.chapter.title)
        }

        val mangaManifestPath = when (target) {
            is ActiveImportTarget.Existing ->
                rootManifest.manga
                    .firstOrNull { it.identity == target.manga.identity.value }
                    ?.path
                    ?: throw LocalImportGlobalFailure("target", localChapter.chapter.title)
            is ActiveImportTarget.CreateNew -> target.manifestPath
            is ActiveImportTarget.Created -> target.manifestPath
        }
        val remoteMangaManifest = when (target) {
            is ActiveImportTarget.Existing -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
                ?: throw LocalImportGlobalFailure("target", localChapter.chapter.title)
            is ActiveImportTarget.CreateNew -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
            is ActiveImportTarget.Created -> webDav.get(config.rootPath.childPath(mangaManifestPath))
                ?.let { decodeMangaManifest(it) }
                ?: throw LocalImportGlobalFailure("target", localChapter.chapter.title)
        }
        val mangaIdentity = when (target) {
            is ActiveImportTarget.Existing -> target.manga.identity.value
            is ActiveImportTarget.CreateNew -> target.mangaIdentity
            is ActiveImportTarget.Created -> target.mangaIdentity
        }
        val now = System.currentTimeMillis()
        val existingRemoteChapters = remoteMangaManifest?.chapters.orEmpty()
        val existingRemoteChaptersByFileKey = existingRemoteChapters
            .associateBy { it.content.path.substringAfterLast('/').duplicateFileKey() }
        val replacement = existingRemoteChaptersByFileKey[
            localChapter.chapter.sourceFileName.duplicateFileKey(),
        ]
        if (replacement != null && !allowReplacement) {
            error("unconfirmed_duplicate")
        }

        progressPhase(VaultImportProgressPhase.COMPRESSING)
        val preparedChapter = chapterStager.stageForUpload(localChapter, stagingRoot)
        val chapterIdentity = replacement?.identity ?: UUID.randomUUID().toString()
        val contentIdentity = if (replacement == null) chapterIdentity else UUID.randomUUID().toString()
        var contentPath: String? = null
        var newCoverPath: String? = null
        try {
            progressPhase(VaultImportProgressPhase.UPLOADING)
            contentPath = uploadChapter(
                webDav = webDav,
                config = config,
                mangaIdentity = mangaIdentity,
                contentIdentity = contentIdentity,
                localChapter = preparedChapter,
            )
            val manifestChapter = if (replacement != null) {
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
            val replacedChapterIdentities = setOfNotNull(replacement?.identity)
            val metadata = when (target) {
                is ActiveImportTarget.Existing -> target.manga.metadata
                is ActiveImportTarget.CreateNew,
                is ActiveImportTarget.Created,
                -> importManga.metadata
            }
            val importedCover = remoteMangaManifest?.cover ?: runCatching {
                progressPhase(VaultImportProgressPhase.UPLOADING)
                uploadCover(
                    webDav = webDav,
                    config = config,
                    mangaIdentity = mangaIdentity,
                    coverFile = coverFile,
                    now = now,
                )?.also { newCoverPath = it.path }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
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
                    } + manifestChapter,
                    replacementIdentities = replacedChapterIdentities,
                ),
                provenance = remoteMangaManifest?.provenance ?: VaultManifestProvenance(
                    importedFrom = "local",
                    sourceName = scanner.localSourceName(),
                    sourceUri = importManga.localMangaIdentity,
                    importedAt = now,
                ),
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
                    newCoverPath = newCoverPath,
                )
                throw LocalImportGlobalFailure("publish", localChapter.chapter.title)
            }

            replacement?.content?.path?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            if (replacement != null) {
                invalidateReplacementCacheState(mangaIdentity, replacement.identity)
            }
            progressPhase(VaultImportProgressPhase.REFRESHING)
            refreshLocalIndex(vaultIdentity, mangaIdentity)
                .takeIf { it != -1L }
                ?.let { vaultMangaId ->
                    repository.upsertImportTargetHint(
                        ImportTargetHint(
                            localMangaId = localManga.id,
                            localMangaIdentity = importManga.localMangaIdentity,
                            contentVaultIdentity = vaultIdentity,
                            sourceIdentity = importManga.localMangaIdentity,
                            vaultMangaIdentity = VaultIdentity(mangaIdentity),
                            vaultMangaId = vaultMangaId,
                            updatedAt = now,
                        ),
                    )
                }

            val nextTarget = when (target) {
                is ActiveImportTarget.Existing -> target
                is ActiveImportTarget.CreateNew,
                is ActiveImportTarget.Created,
                -> ActiveImportTarget.Created(
                    mangaIdentity = mangaIdentity,
                    manifestPath = mangaManifestPath,
                )
            }
            return PublishedLocalChapter(
                target = nextTarget,
                replaced = replacement != null,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is LocalImportGlobalFailure) throw error
            contentPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            newCoverPath?.let { runCatching { webDav.delete(config.rootPath.childPath(it)) } }
            throw error
        }
    }

    private suspend fun configuredVault() =
        preferences.configuredVaultIdentity.get()
            .takeIf { it.isNotBlank() }
            ?.let { repository.getVaultByIdentity(ContentVaultIdentity(it)) }

    private suspend fun existingChaptersByMangaId(vaultId: Long): Map<Long, List<VaultChapter>> {
        return repository.getChaptersForVault(vaultId).groupBy { it.mangaId }
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
        localChapter: ScannedLocalVaultChapter,
    ): String {
        val basePath = "content/$mangaIdentity/$contentIdentity"
        val remoteBasePath = config.rootPath.childPath(basePath)
        webDav.createDirectory(config.rootPath.childPath("content"))
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

    private suspend fun invalidateReplacementCacheState(
        mangaIdentity: String,
        replacedChapterIdentity: String,
    ) {
        val vaultId = preferences.configuredVaultIdentity.get()
            .takeIf { it.isNotBlank() }
            ?.let { repository.getVaultByIdentity(ContentVaultIdentity(it)) }
            ?.id
            ?: return
        val mangaId = repository.getManga(vaultId)
            .firstOrNull { it.identity.value == mangaIdentity }
            ?.id
            ?: return
        val replacedChapterIds = repository.getChapters(mangaId)
            .filter { it.identity.value == replacedChapterIdentity }
            .map { it.id }
        repository.deleteCacheStates(replacedChapterIds)
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

    private sealed interface ActiveImportTarget {
        val mangaIdentity: String?

        data class Existing(
            val manga: VaultManga,
            val reason: LocalVaultImportTarget.Reason,
        ) : ActiveImportTarget {
            override val mangaIdentity: String = manga.identity.value
        }

        data class CreateNew(
            override val mangaIdentity: String = UUID.randomUUID().toString(),
            val manifestPath: String = "manga/${UUID.randomUUID()}.json",
        ) : ActiveImportTarget

        data class Created(
            override val mangaIdentity: String,
            val manifestPath: String,
        ) : ActiveImportTarget
    }

    private fun ImportTarget.toActiveImportTarget(): ActiveImportTarget {
        return when (this) {
            is ImportTarget.Existing -> ActiveImportTarget.Existing(manga, reason)
            ImportTarget.CreateNew -> ActiveImportTarget.CreateNew()
        }
    }

    private fun ImportTarget.metadata(localManga: LocalVaultImportManga): VaultMetadata {
        return when (this) {
            is ImportTarget.Existing -> manga.metadata
            ImportTarget.CreateNew -> localManga.metadata
        }
    }

    private fun importStagingRoot(): File = File(app.cacheDir, "content-vault-import")

    private fun ScannedLocalVaultChapter.toManifestChapter(
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

    private fun ScannedLocalVaultChapter.toReplacementManifestChapter(
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

    private data class PublishedLocalChapter(
        val target: ActiveImportTarget,
        val replaced: Boolean,
    )

    private class LocalImportGlobalFailure(
        val category: String,
        val chapterTitle: String?,
    ) : RuntimeException(category)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_NOT_FOUND = 404
    }
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
        val addedChapterCount: Int,
        val replacedChapterCount: Int,
        val failedChapterCount: Int,
    ) : LocalVaultImportResult
}
