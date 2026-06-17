package eu.kanade.tachiyomi.data.vault.capture

import android.content.Context
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultImportProgress
import eu.kanade.tachiyomi.data.vault.localimport.VaultImportProgressPhase
import eu.kanade.tachiyomi.data.vault.localimport.deleteRecursively
import eu.kanade.tachiyomi.data.vault.refresh.AddToVaultIndexRefresher
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import eu.kanade.tachiyomi.data.vault.transfer.AddToVaultChapterFailure
import eu.kanade.tachiyomi.data.vault.transfer.AddToVaultTransferFinalizer
import eu.kanade.tachiyomi.data.vault.webdav.LibraryVaultCaptureWebDavFactoryBoundary
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.vault.interactor.BuildLibraryVaultCapturePlan
import tachiyomi.domain.vault.interactor.duplicateTitleKey
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LibraryVaultCaptureChapter
import tachiyomi.domain.vault.model.LibraryVaultCaptureManga
import tachiyomi.domain.vault.model.LibraryVaultCapturePlan
import tachiyomi.domain.vault.model.LibraryVaultCaptureTarget
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.File
import java.util.UUID

internal class LibraryVaultCaptureService(
    private val context: Context,
    private val webDavFactory: LibraryVaultCaptureWebDavFactoryBoundary,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val sourceManager: SourceManager,
    private val refreshService: VaultCatalogueRefresher,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val chapterPublisher: LibraryVaultChapterPublisherBoundary,
) {
    private val planner = BuildLibraryVaultCapturePlan()
    private val indexRefresher = AddToVaultIndexRefresher(repository, refreshService)

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
            .map { AddToVaultChapterFailure(it, "missing_chapter") }
            .toMutableList()
        val stagingRoot = captureStagingRoot().apply {
            deleteRecursively()
            mkdirs()
        }
        val webDav = webDavFactory.create(config)
        val progressTotal = selectedChapters.size.coerceAtLeast(1)
        var activeTarget = target

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
                        chapterPublisher.publish(
                            webDav = webDav,
                            config = config,
                            vaultIdentity = vault.identity,
                            expectedVaultIdentity = expectedVaultIdentity,
                            source = source,
                            manga = manga,
                            captureManga = captureManga,
                            chapter = chapter,
                            target = activeTarget,
                            stagingRoot = chapterStagingRoot,
                            allowReplacement = chapter.url in allowedReplacementChapterIds,
                            progressPhase = ::updatePhase,
                        )
                    } finally {
                        chapterStagingRoot.deleteRecursively()
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    if (error is LibraryCaptureGlobalFailure) throw error
                    failures += AddToVaultChapterFailure(chapter.name, error.captureFailureCategory())
                    return@forEachIndexed
                }
                updatePhase(VaultImportProgressPhase.REFRESHING)
                indexRefresher.refreshPublishedMangaId(vault.identity, published.mangaIdentity.value)
                    ?.let { vaultMangaId ->
                        repository.upsertImportTargetHint(
                            ImportTargetHint(
                                localMangaId = manga.id,
                                localMangaIdentity = manga.url,
                                contentVaultIdentity = vault.identity,
                                sourceIdentity = captureManga.sourceIdentity,
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
                progress(
                    LocalVaultImportProgress(
                        current = index + 1,
                        total = progressTotal,
                        chapterTitle = chapter.name,
                    ),
                )
            }
        } catch (e: CancellationException) {
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
        } catch (e: LibraryCaptureGlobalFailure) {
            val completedAt = System.currentTimeMillis()
            val globalFailure = AddToVaultChapterFailure(e.chapterTitle ?: manga.title, e.category)
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
                LibraryVaultCaptureResult.Captured(
                    addedChapterCount = added,
                    replacedChapterCount = replaced,
                    failedChapterCount = failures.size + 1,
                )
            } else {
                LibraryVaultCaptureResult.UploadFailed
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

        return if (added + replaced > 0) {
            LibraryVaultCaptureResult.Captured(
                addedChapterCount = added,
                replacedChapterCount = replaced,
                failedChapterCount = failures.size,
            )
        } else {
            LibraryVaultCaptureResult.UploadFailed
        }
    }

    private suspend fun configuredVault() =
        preferences.configuredVaultIdentity.get()
            .takeIf { it.isNotBlank() }
            ?.let { repository.getVaultByIdentity(ContentVaultIdentity(it)) }

    private fun resolveTarget(
        target: LibraryVaultCaptureTarget,
        vaultManga: List<VaultManga>,
        targetMangaId: Long?,
        createNew: Boolean,
    ): LibraryVaultActiveTarget? {
        if (createNew) {
            return LibraryVaultActiveTarget.CreateNew()
        }
        targetMangaId
            ?.let { id -> vaultManga.firstOrNull { it.id == id } }
            ?.let {
                return LibraryVaultActiveTarget.Existing(
                    manga = it,
                    reason = LibraryVaultCaptureTarget.Reason.USER_SELECTED,
                )
            }
        return when (target) {
            LibraryVaultCaptureTarget.CreateNew -> LibraryVaultActiveTarget.CreateNew()
            is LibraryVaultCaptureTarget.Existing -> LibraryVaultActiveTarget.Existing(target.manga, target.reason)
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

    private fun captureStagingRoot(): File = File(context.cacheDir, "content-vault-capture")

    private fun Throwable.captureFailureCategory(): String {
        return message?.takeIf {
            it in setOf(
                "downloaded_copy",
                "download",
                "empty_pages",
                "staging",
                "upload",
                "chapter_upload",
                "cover_upload",
                "publish",
                "manifest",
                "target",
                "identity",
                "unconfirmed_duplicate",
            )
        } ?: "capture_failed"
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
    data object UploadFailed : LibraryVaultCaptureResult
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
