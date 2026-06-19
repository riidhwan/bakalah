package eu.kanade.tachiyomi.data.vault.localimport

import android.app.Application
import eu.kanade.tachiyomi.data.vault.add.AddToVaultProgress
import eu.kanade.tachiyomi.data.vault.add.AddToVaultProgressPhase
import eu.kanade.tachiyomi.data.vault.refresh.AddToVaultIndexRefresher
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.webdav.WebDavVaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.staging.deleteRecursively
import eu.kanade.tachiyomi.data.vault.transfer.AddToVaultChapterFailure
import eu.kanade.tachiyomi.data.vault.transfer.AddToVaultTransferFinalizer
import eu.kanade.tachiyomi.data.vault.webdav.RemoteVaultWebDav
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.vault.interactor.BuildLocalVaultImportPlan
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LocalVaultImportPlan
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.File
import java.util.UUID

class LocalVaultImportService internal constructor(
    private val app: Application,
    networkHelper: NetworkHelper,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val refreshService: VaultCatalogueRefresher,
    private val scanner: LocalVaultMangaScannerBoundary,
    private val chapterPublisher: LocalVaultChapterPublisherBoundary,
) {
    private val remoteStorageFactory: VaultRemoteStorageFactory = WebDavVaultRemoteStorageFactory(networkHelper)
    private val planner = BuildLocalVaultImportPlan()
    private val indexRefresher = AddToVaultIndexRefresher(repository, refreshService)

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
        importRequest: VaultImportRequest? = null,
        selectedChapterIds: Set<String>? = null,
        allowedReplacementChapterIds: Set<String> = emptySet(),
        targetMangaId: Long? = null,
        createNew: Boolean = false,
        createNewTitle: String? = null,
        progress: (AddToVaultProgress) -> Unit = {},
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

        val pendingRequestChapters = importRequest?.pendingTaskItems.orEmpty()
        val selectedIds = when {
            importRequest != null -> pendingRequestChapters.map { it.selectionId }.toSet()
            selectedChapterIds != null -> selectedChapterIds
            else ->
                plan.chapters
                    .filter { it.selectedByDefault }
                    .map { it.chapter.selectionId }
                    .toSet()
        }
        val selectedChapters = scan.chapters.filter { it.chapter.selectionId in selectedIds }
        if (selectedIds.isEmpty() || (selectedChapters.isEmpty() && selectedChapterIds == null)) {
            return LocalVaultImportResult.NothingSelected(plan)
        }
        val progressTotal = selectedChapters.size.coerceAtLeast(1)

        val webDav = RemoteVaultWebDav(remoteStorageFactory.create(config))
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
        val missingSelectedIds = selectedIds - selectedChapters.map { it.chapter.selectionId }.toSet()
        missingSelectedIds.forEach { selectionId ->
            importRequest?.markRunning(selectionId)
            importRequest?.markFailed(selectionId, "missing_chapter")
        }
        val failures = missingSelectedIds
            .map { AddToVaultChapterFailure(it, "missing_chapter") }
            .toMutableList()
        val stagingRoot = importStagingRoot().apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            selectedChapters.forEachIndexed { index, localChapter ->
                currentCoroutineContext().ensureActive()
                importRequest?.markRunning(localChapter.chapter.selectionId)
                fun updatePhase(phase: AddToVaultProgressPhase? = null, indeterminate: Boolean = false) {
                    progress(
                        AddToVaultProgress(
                            current = index,
                            total = progressTotal,
                            chapterTitle = localChapter.chapter.title,
                            indeterminate = indeterminate,
                            phase = phase,
                        ),
                    )
                }
                updatePhase(AddToVaultProgressPhase.PREPARING, indeterminate = true)
                val chapterStagingRoot = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
                val published = runCatching {
                    try {
                        chapterPublisher.publish(
                            webDav = webDav,
                            config = config,
                            vaultIdentity = vault.identity,
                            expectedVaultIdentity = expectedVaultIdentity,
                            importManga = scan.manga,
                            localChapter = localChapter,
                            coverFile = scan.coverFile,
                            target = activeTarget,
                            allowReplacement = localChapter.chapter.selectionId in allowedReplacementChapterIds,
                            stagingRoot = chapterStagingRoot,
                            localSourceName = scanner.localSourceName(),
                            progressPhase = { phase -> updatePhase(phase, indeterminate = true) },
                        )
                    } finally {
                        chapterStagingRoot.deleteRecursively()
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    if (error is LocalImportGlobalFailure) throw error
                    importRequest?.markFailed(
                        selectionId = localChapter.chapter.selectionId,
                        failureCategory = error.localImportFailureCategory(),
                    )
                    failures +=
                        AddToVaultChapterFailure(localChapter.chapter.title, error.localImportFailureCategory())
                    return@forEachIndexed
                }
                updatePhase(AddToVaultProgressPhase.REFRESHING, indeterminate = true)
                indexRefresher.refreshPublishedMangaId(vault.identity, published.mangaIdentity.value)
                    ?.let { vaultMangaId ->
                        repository.upsertImportTargetHint(
                            ImportTargetHint(
                                localMangaId = localManga.id,
                                localMangaIdentity = scan.manga.localMangaIdentity,
                                contentVaultIdentity = vault.identity,
                                sourceIdentity = scan.manga.localMangaIdentity,
                                vaultMangaIdentity = published.mangaIdentity,
                                vaultMangaId = vaultMangaId,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                activeTarget = published.target
                if (published.replaced) {
                    replaced += 1
                } else {
                    added += 1
                }
                importRequest?.markCompleted(
                    selectionId = localChapter.chapter.selectionId,
                    isReplaced = published.replaced,
                )
                progress(
                    AddToVaultProgress(
                        current = index + 1,
                        total = progressTotal,
                        chapterTitle = localChapter.chapter.title,
                    ),
                )
            }
        } catch (e: CancellationException) {
            importRequest?.markNonTerminalFailed("cancelled")
            repository.upsertTransferJob(
                AddToVaultTransferFinalizer.cancel(
                    job = job,
                    selectedCount = selectedChapters.size,
                    added = added,
                    replaced = replaced,
                    failures = failures,
                    completedAt = System.currentTimeMillis(),
                ),
            )
            throw e
        } catch (e: LocalImportGlobalFailure) {
            val completedAt = System.currentTimeMillis()
            val globalFailure = AddToVaultChapterFailure(e.chapterTitle ?: scan.manga.title, e.category)
            importRequest?.markNonTerminalFailed(e.category, completedAt)
            repository.upsertTransferJob(
                AddToVaultTransferFinalizer.stopAfterGlobalFailure(
                    job = job,
                    selectedCount = selectedChapters.size,
                    added = added,
                    replaced = replaced,
                    failures = failures,
                    globalFailure = globalFailure,
                    completedAt = completedAt,
                ),
            )
            return if (added + replaced > 0) {
                LocalVaultImportResult.Imported(
                    mangaIdentity = activeTarget.mangaIdentity?.let(::VaultIdentity) ?: VaultIdentity(""),
                    addedChapterCount = added,
                    replacedChapterCount = replaced,
                    failedChapterCount = failures.size + 1,
                )
            } else {
                LocalVaultImportResult.UploadFailed
            }
        } finally {
            stagingRoot.deleteRecursively()
        }

        val completedAt = System.currentTimeMillis()
        repository.upsertTransferJob(
            AddToVaultTransferFinalizer.complete(
                job = job,
                added = added,
                replaced = replaced,
                failures = failures,
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

    private sealed interface ImportTarget {
        data class Existing(
            val manga: VaultManga,
            val reason: LocalVaultImportTarget.Reason,
        ) : ImportTarget

        data object CreateNew : ImportTarget
    }

    private fun ImportTarget.toActiveImportTarget(): LocalVaultActiveTarget {
        return when (this) {
            is ImportTarget.Existing -> LocalVaultActiveTarget.Existing(manga, reason)
            ImportTarget.CreateNew -> LocalVaultActiveTarget.CreateNew()
        }
    }

    private fun importStagingRoot(): File = File(app.cacheDir, "content-vault-import")

    private suspend fun VaultImportRequest.markRunning(selectionId: String) {
        repository.markImportRequestChapterRunning(
            requestId = id,
            selectionId = selectionId,
            processedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun VaultImportRequest.markCompleted(selectionId: String, isReplaced: Boolean) {
        repository.markImportRequestChapterCompleted(
            requestId = id,
            selectionId = selectionId,
            isReplaced = isReplaced,
            processedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun VaultImportRequest.markFailed(selectionId: String, failureCategory: String) {
        repository.markImportRequestChapterFailed(
            requestId = id,
            selectionId = selectionId,
            failureCategory = failureCategory,
            processedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun VaultImportRequest.markNonTerminalFailed(
        failureCategory: String,
        processedAt: Long = System.currentTimeMillis(),
    ) {
        repository.markNonTerminalImportRequestChaptersFailed(
            requestId = id,
            failureCategory = failureCategory,
            processedAt = processedAt,
        )
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
