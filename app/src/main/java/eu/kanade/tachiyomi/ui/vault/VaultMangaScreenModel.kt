package eu.kanade.tachiyomi.ui.vault

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.vault.cache.VaultCacheEvictionResult
import eu.kanade.tachiyomi.data.vault.cache.VaultCachePolicyService
import eu.kanade.tachiyomi.data.vault.export.VaultChapterExportResult
import eu.kanade.tachiyomi.data.vault.export.VaultChapterExportService
import eu.kanade.tachiyomi.data.vault.export.vaultChapterRemotePath
import eu.kanade.tachiyomi.data.vault.operation.VaultMetadataLabelEditPayload
import eu.kanade.tachiyomi.data.vault.operation.VaultMetadataPublishPayload
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationManager
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailDisplayLoader
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailDisplayResult
import eu.kanade.tachiyomi.data.vault.publishing.VaultCoverPublishService
import eu.kanade.tachiyomi.data.vault.publishing.VaultMangaDeletionResult
import eu.kanade.tachiyomi.data.vault.publishing.VaultMangaDeletionService
import eu.kanade.tachiyomi.data.vault.transfer.UniFileVaultTransferLocalStaging
import eu.kanade.tachiyomi.data.vault.transfer.VaultTransferResult
import eu.kanade.tachiyomi.data.vault.transfer.VaultTransferService
import eu.kanade.tachiyomi.data.vault.transfer.WebDavVaultTransferStorage
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.vault.interactor.duplicateTitleKey
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

class VaultMangaScreenModel(
    private val mangaId: Long,
    private val repository: VaultRepository = Injekt.get(),
    private val preferences: ContentVaultPreferences = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val deletionService: VaultMangaDeletionService = Injekt.get(),
    private val coverPublishService: VaultCoverPublishService = Injekt.get(),
    private val operationManager: VaultOperationManager = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val chapterThumbnailDisplayLoader: VaultChapterThumbnailDisplayLoader = Injekt.get(),
    private val chapterExportService: VaultChapterExportService = Injekt.get(),
) : StateScreenModel<VaultMangaScreenModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()
    private val loadingChapterThumbnailIds = Collections.synchronizedSet(mutableSetOf<Long>())
    private val observedTerminalMetadataJobIds = Collections.synchronizedSet(mutableSetOf<Long>())

    init {
        screenModelScope.launchIO {
            val manga = repository.getMangaById(mangaId)
            val mangaLabels = manga?.let { loadedManga -> repository.getLabelsForManga(loadedManga.id) }.orEmpty()
            val vaultLabels = manga?.let { loadedManga -> repository.getLabels(loadedManga.vaultId) }.orEmpty()
            val config = preferences.getWebDavConfig()
            mutableState.update {
                it.copy(
                    manga = manga,
                    isLoading = manga != null,
                    mangaLabels = mangaLabels,
                    vaultLabels = vaultLabels,
                    configuredVaultRootPath = config.rootPath.takeIf { config.isComplete },
                )
            }
            if (manga == null) {
                _events.send(Event.LoadFailed)
                return@launchIO
            }
            observedTerminalMetadataJobIds.addAll(
                repository.getTransferJobsForVault(manga.vaultId)
                    .filter { it.isCompletedMetadataOperationForCurrentManga() }
                    .map { it.id },
            )
            reloadCoverCache()
            recoverInterruptedCacheJobs(manga)

            screenModelScope.launchIO {
                combine(
                    repository.getMangaAsFlow(manga.vaultId),
                    repository.getLabelsAsFlow(manga.vaultId),
                    repository.getLabelsByMangaForVaultAsFlow(manga.vaultId),
                    repository.getTransferJobsForMangaAsFlow(mangaId),
                ) { mangaList, vaultLabels, labelsByManga, transferJobs ->
                    val authoritativeManga = mangaList.firstOrNull { it.id == mangaId }
                    val authoritativeMangaLabels = labelsByManga[mangaId].orEmpty()
                    val overlay = transferJobs.latestMetadataOverlay(json)
                    VaultMangaMetadataSnapshot(
                        manga = authoritativeManga?.withOverlay(overlay),
                        mangaLabels = overlay?.mangaLabels(vaultLabels) ?: authoritativeMangaLabels,
                        vaultLabels = overlay?.vaultLabels(vaultLabels) ?: vaultLabels,
                        pendingLabelIdentities = overlay?.pendingLabelIdentities(authoritativeMangaLabels).orEmpty(),
                        isPublishingMetadata = transferJobs.any {
                            it.type == VaultTransferType.METADATA_PUBLISH &&
                                it.mangaId == mangaId &&
                                (it.state == VaultTransferState.QUEUED || it.state == VaultTransferState.RUNNING)
                        },
                        terminalJobs = transferJobs.filter { it.isCompletedMetadataOperationForCurrentManga() },
                    )
                }
                    .catch {
                        logcat(LogPriority.ERROR, it)
                        _events.send(Event.LoadFailed)
                    }
                    .collectLatest { snapshot ->
                        mutableState.update {
                            it.copy(
                                manga = snapshot.manga,
                                mangaLabels = snapshot.mangaLabels,
                                vaultLabels = snapshot.vaultLabels,
                                pendingLabelIdentities = snapshot.pendingLabelIdentities,
                                isPublishingMetadata = snapshot.isPublishingMetadata,
                            )
                        }
                        snapshot.terminalJobs.forEach { job ->
                            if (observedTerminalMetadataJobIds.add(job.id)) {
                                if (job.state == VaultTransferState.SUCCEEDED) {
                                    mutableState.update {
                                        it.copy(metadataPublishSuccessCount = it.metadataPublishSuccessCount + 1)
                                    }
                                    _events.send(Event.MetadataPublished)
                                } else {
                                    _events.send(
                                        Event.MetadataPublishFailed(
                                            job.failureReason.toMetadataPublishFailureDetail(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
            }

            combine(
                repository.getChaptersAsFlow(mangaId),
                repository.getCacheStatesForMangaAsFlow(mangaId),
            ) { chapters, cacheStates ->
                buildVaultChapterItems(
                    chapters = chapters,
                    cacheStates = cacheStates,
                    previousItems = mutableState.value.chapters,
                )
            }
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.LoadFailed)
                }
                .collectLatest { chapters ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            chapters = orderVaultMangaDetailChapters(chapters),
                            localCacheUsageBytes = repository.getLocalCacheUsageBytes(manga.vaultId),
                            vaultStorageUsageBytes = chapters.sumOf { item ->
                                item.chapter.content.sizeBytes + (item.chapter.thumbnail?.sizeBytes ?: 0L)
                            },
                        )
                    }
                }
        }
    }

    fun loadChapterThumbnail(item: VaultChapterItem) {
        if (!item.needsThumbnailLoad) return
        if (!loadingChapterThumbnailIds.add(item.chapter.id)) return
        screenModelScope.launchIO {
            try {
                val manga = mutableState.value.manga ?: return@launchIO
                val localResult = chapterThumbnailDisplayLoader.loadLocal(manga, item.chapter)
                val result = if (localResult is VaultChapterThumbnailDisplayResult.Ready) {
                    localResult
                } else {
                    chapterThumbnailDisplayLoader.load(manga, item.chapter)
                }
                mutableState.update { state ->
                    state.copy(
                        chapters = state.chapters.map { current ->
                            if (
                                current.chapter.id == item.chapter.id &&
                                current.chapter.thumbnail?.identity == item.chapter.thumbnail?.identity
                            ) {
                                current.copy(thumbnail = result)
                            } else {
                                current
                            }
                        },
                    )
                }
            } finally {
                loadingChapterThumbnailIds.remove(item.chapter.id)
            }
        }
    }

    fun reportUnavailable(action: VaultScreenModel.PendingAction) {
        screenModelScope.launchIO {
            _events.send(Event.PendingActionUnavailable(action))
        }
    }

    fun cacheChapter(item: VaultChapterItem) {
        screenModelScope.launchIO {
            runCatching {
                val manga = mutableState.value.manga ?: return@launchIO
                val config = preferences.getWebDavConfig()
                if (!config.isComplete) return@runCatching VaultTransferResult.Failed("incomplete configuration")

                val localStaging = localStaging() ?: return@runCatching VaultTransferResult.Failed("cache unavailable")
                val cachePolicy = cachePolicyService(localStaging)
                val service = transferService(config, localStaging)
                val localPath = cachePolicy.cachePath(manga, item.chapter)
                val jobId = service.enqueue(
                    vaultId = manga.vaultId,
                    type = VaultTransferType.CACHE_CHAPTER,
                    chapterId = item.chapter.id,
                    remotePath = config.rootPath.childPath(item.chapter.content.path),
                    localPath = localPath,
                    sizeBytes = item.chapter.content.sizeBytes,
                    checksumSha256 = item.chapter.content.checksumSha256,
                )
                service.execute(jobId).also { result ->
                    if (result == VaultTransferResult.Succeeded) {
                        cachePolicy.enforceLimit(
                            vaultId = manga.vaultId,
                            protectedChapterIds = setOf(item.chapter.id),
                        )
                    }
                }
            }.getOrElse {
                VaultTransferResult.Failed(it.message ?: "cache failed")
            }.let { result ->
                if (result != VaultTransferResult.Succeeded) {
                    _events.send(Event.CacheFailed)
                }
            }
        }
    }

    fun retryChapter(item: VaultChapterItem) {
        screenModelScope.launchIO {
            runCatching {
                val manga = mutableState.value.manga ?: return@launchIO
                val config = preferences.getWebDavConfig()
                if (!config.isComplete) return@runCatching VaultTransferResult.Failed("incomplete configuration")
                val localStaging = localStaging() ?: return@runCatching VaultTransferResult.Failed("cache unavailable")
                val job = repository.getTransferJobsForVault(manga.vaultId)
                    .lastOrNull {
                        it.chapterId == item.chapter.id &&
                            (
                                it.state == VaultTransferState.QUEUED ||
                                    it.state == VaultTransferState.FAILED ||
                                    it.state == VaultTransferState.INTEGRITY_FAULT
                                )
                    }
                    ?: return@runCatching null

                val service = transferService(config, localStaging)
                val cachePolicy = cachePolicyService(localStaging)
                if (job.state == VaultTransferState.QUEUED) {
                    service.execute(job.id)
                } else {
                    service.retry(job.id)
                }.also { result ->
                    if (result == VaultTransferResult.Succeeded) {
                        cachePolicy.enforceLimit(
                            vaultId = manga.vaultId,
                            protectedChapterIds = setOf(item.chapter.id),
                        )
                    }
                }
            }.getOrElse {
                VaultTransferResult.Failed(it.message ?: "cache retry failed")
            }?.let { result ->
                if (result != VaultTransferResult.Succeeded) {
                    _events.send(Event.CacheFailed)
                }
            } ?: cacheChapter(item)
        }
    }

    private suspend fun recoverInterruptedCacheJobs(manga: VaultManga) {
        runCatching {
            val config = preferences.getWebDavConfig()
            if (!config.isComplete) return
            val chapters = repository.getChapters(manga.id)
            val chapterIds = chapters.map { it.id }.toSet()
            val localStaging = localStaging() ?: return
            val service = transferService(config, localStaging)
            val cachePolicy = cachePolicyService(localStaging)
            val activeCacheJobs = repository.getTransferJobsForVault(manga.vaultId)
                .filter {
                    it.type == VaultTransferType.CACHE_CHAPTER &&
                        (it.state == VaultTransferState.QUEUED || it.state == VaultTransferState.RUNNING) &&
                        it.chapterId in chapterIds
                }
            activeCacheJobs.forEach {
                if (service.execute(it.id) == VaultTransferResult.Succeeded) {
                    cachePolicy.enforceLimit(
                        vaultId = manga.vaultId,
                        protectedChapterIds = setOfNotNull(it.chapterId),
                    )
                }
            }
            val activeCacheJobChapterIds = activeCacheJobs.mapNotNull { it.chapterId }.toSet()
            chapters.forEach { chapter ->
                val state = repository.getCacheState(chapter.id) ?: return@forEach
                if (state.state in INTERRUPTED_CACHE_STATES && chapter.id !in activeCacheJobChapterIds) {
                    repository.upsertCacheState(
                        state.copy(
                            state = VaultCacheState.VAULT_ONLY,
                            localPath = null,
                            sizeBytes = null,
                            checksumSha256 = null,
                            lastVerifiedAt = null,
                            updatedAt = System.currentTimeMillis(),
                            failureReason = null,
                        ),
                    )
                }
            }
        }.onFailure {
            logcat(LogPriority.ERROR, it)
        }
    }

    fun evictChapter(item: VaultChapterItem) {
        screenModelScope.launchIO {
            runCatching {
                val localStaging = localStaging() ?: return@runCatching false
                cachePolicyService(localStaging).evictChapter(item.chapter.id) == VaultCacheEvictionResult.Evicted
            }.getOrElse {
                logcat(LogPriority.ERROR, it)
                false
            }.let { evicted ->
                if (!evicted) _events.send(Event.CacheFailed)
            }
        }
    }

    fun exportChapter(item: VaultChapterItem) {
        screenModelScope.launchIO {
            val currentState = mutableState.value
            val manga = currentState.manga ?: return@launchIO
            if (item.chapter.content.format != VaultChapterContentFormat.CBZ) {
                _events.send(Event.ChapterExportFailed("Unsupported chapter format"))
                return@launchIO
            }
            if (item.state in EXPORT_BLOCKED_STATES) {
                _events.send(Event.ChapterExportFailed("Chapter is busy"))
                return@launchIO
            }
            if (item.chapter.id in currentState.exportingChapterIds) return@launchIO

            mutableState.update { it.copy(exportingChapterIds = it.exportingChapterIds + item.chapter.id) }
            try {
                val result = try {
                    val latestItem =
                        mutableState.value.chapters.firstOrNull { it.chapter.id == item.chapter.id } ?: item
                    chapterExportService.export(
                        manga = manga,
                        chapter = latestItem.chapter,
                        cacheState = latestItem.cacheState,
                        localStaging = localStaging(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e)
                    VaultChapterExportResult.SaveFailed
                }
                when (result) {
                    is VaultChapterExportResult.Exported -> _events.send(Event.ChapterExported(result.filename))
                    else -> _events.send(Event.ChapterExportFailed(result.toFailureDetail()))
                }
            } finally {
                mutableState.update { it.copy(exportingChapterIds = it.exportingChapterIds - item.chapter.id) }
            }
        }
    }

    fun exportChapterThumbnail(item: VaultChapterItem) {
        screenModelScope.launchIO {
            val currentState = mutableState.value
            val manga = currentState.manga ?: return@launchIO
            if (item.chapter.thumbnail == null) {
                _events.send(Event.ChapterExportFailed("Remote thumbnail file not found"))
                return@launchIO
            }
            if (item.chapter.id in currentState.exportingThumbnailChapterIds) return@launchIO

            mutableState.update {
                it.copy(exportingThumbnailChapterIds = it.exportingThumbnailChapterIds + item.chapter.id)
            }
            try {
                val result = try {
                    val latestItem =
                        mutableState.value.chapters.firstOrNull { it.chapter.id == item.chapter.id } ?: item
                    chapterExportService.exportThumbnail(
                        manga = manga,
                        chapter = latestItem.chapter,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e)
                    VaultChapterExportResult.SaveFailed
                }
                when (result) {
                    is VaultChapterExportResult.Exported -> _events.send(Event.ChapterExported(result.filename))
                    else -> _events.send(Event.ChapterExportFailed(result.toFailureDetail()))
                }
            } finally {
                mutableState.update {
                    it.copy(exportingThumbnailChapterIds = it.exportingThumbnailChapterIds - item.chapter.id)
                }
            }
        }
    }

    fun deleteManga() {
        screenModelScope.launchIO {
            val manga = mutableState.value.manga ?: return@launchIO
            mutableState.update { it.copy(isDeleting = true) }
            val result = runCatching {
                deletionService.delete(manga.id)
            }.getOrElse {
                logcat(LogPriority.ERROR, it)
                VaultMangaDeletionResult.PublishFailed
            }
            when (result) {
                is VaultMangaDeletionResult.Deleted,
                is VaultMangaDeletionResult.DeletedWithCleanupFailures,
                -> {
                    _events.send(Event.DeleteCompleted(result.cleanupWarningDetail()))
                }
                else -> _events.send(Event.DeleteFailed(result.toFailureDetail()))
            }
            mutableState.update { it.copy(isDeleting = false) }
        }
    }

    fun publishMetadata(edit: VaultMetadataEdit) {
        screenModelScope.launchIO {
            val manga = mutableState.value.manga ?: return@launchIO
            val result = runCatching {
                operationManager.enqueueMetadataPublish(
                    vaultId = manga.vaultId,
                    payload = VaultMetadataPublishPayload(
                        mangaId = mangaId,
                        title = edit.title,
                        author = edit.author,
                        artist = edit.artist,
                        description = edit.description,
                        status = edit.status,
                        labels = edit.labels.map {
                            VaultMetadataLabelEditPayload(
                                identity = it.identity,
                                name = it.name,
                                isSensitive = it.isSensitive,
                                assigned = it.assigned,
                            )
                        },
                    ),
                )
            }.getOrElse {
                logcat(LogPriority.ERROR, it)
                null
            }
            if (result == null) _events.send(Event.MetadataPublishFailed("Could not enqueue Vault metadata publish"))
        }
    }

    fun assignLabel(label: VaultLabel) {
        publishLabelEdit(
            editLabel = { edit ->
                edit.copy(assigned = edit.matches(label) || edit.assigned)
            },
        )
    }

    fun createLabel(name: String) {
        val labelName = name.trim()
        if (labelName.isBlank()) return
        publishLabelEdit(
            editLabel = { it },
            extraLabels = {
                listOf(
                    VaultLabelEdit(
                        identity = null,
                        name = labelName,
                        isSensitive = false,
                        assigned = true,
                    ),
                )
            },
        )
    }

    fun removeLabelAssignment(label: VaultLabel) {
        publishLabelEdit(
            editLabel = { edit ->
                if (edit.matches(label)) {
                    edit.copy(assigned = false)
                } else {
                    edit
                }
            },
        )
    }

    fun toggleLabelSensitivity(label: VaultLabel) {
        publishLabelEdit(
            editLabel = { edit ->
                if (edit.matches(label)) {
                    edit.copy(isSensitive = !label.isSensitive)
                } else {
                    edit
                }
            },
        )
    }

    private fun publishLabelEdit(
        editLabel: (VaultLabelEdit) -> VaultLabelEdit,
        extraLabels: () -> List<VaultLabelEdit> = { emptyList() },
    ) {
        val currentState = mutableState.value
        val manga = currentState.manga ?: return
        publishMetadata(
            VaultMetadataEdit(
                title = manga.metadata.title,
                author = manga.metadata.author.orEmpty(),
                artist = manga.metadata.artist.orEmpty(),
                description = manga.metadata.description.orEmpty(),
                status = manga.metadata.status,
                labels = currentState.labelEdits().map(editLabel) + extraLabels(),
            ),
        )
    }

    private fun VaultLabelEdit.matches(label: VaultLabel): Boolean {
        return if (isPendingLabelIdentity(label.identity.value)) {
            identity == null && VaultMetadata.normalizeTitle(name) == VaultMetadata.normalizeTitle(label.name)
        } else {
            identity == label.identity.value
        }
    }

    private suspend fun reloadCoverCache() {
        val coverUri = runCatching {
            coverPublishService.cacheCover(mangaId)
        }.getOrElse {
            logcat(LogPriority.ERROR, it)
            null
        }
        mutableState.update { it.copy(coverUri = coverUri) }
    }

    private fun transferService(
        config: WebDavVaultConfig,
        localStaging: UniFileVaultTransferLocalStaging,
    ): VaultTransferService {
        return VaultTransferService(
            repository = repository,
            remoteStorage = WebDavVaultTransferStorage(networkHelper, config),
            localStaging = localStaging,
        )
    }

    private fun cachePolicyService(localStaging: UniFileVaultTransferLocalStaging): VaultCachePolicyService {
        return VaultCachePolicyService(
            repository = repository,
            localStaging = localStaging,
            preferences = preferences,
        )
    }

    private fun localStaging(): UniFileVaultTransferLocalStaging? {
        return storageManager.getVaultCacheDirectory()?.let(::UniFileVaultTransferLocalStaging)
    }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    private companion object {
        val INTERRUPTED_CACHE_STATES = setOf(VaultCacheState.QUEUED, VaultCacheState.CACHING)
        val EXPORT_BLOCKED_STATES = setOf(
            VaultCacheState.QUEUED,
            VaultCacheState.CACHING,
            VaultCacheState.PUBLISHING,
        )
    }

    data class VaultChapterItem(
        val chapter: VaultChapter,
        val cacheState: VaultChapterCacheState?,
        val thumbnail: VaultChapterThumbnailDisplayResult,
    ) {
        val state: VaultCacheState
            get() = cacheState?.state ?: VaultCacheState.VAULT_ONLY

        val thumbnailUri: String?
            get() = (thumbnail as? VaultChapterThumbnailDisplayResult.Ready)?.localUri

        val needsThumbnailLoad: Boolean
            get() = chapter.thumbnail != null && thumbnail == VaultChapterThumbnailDisplayResult.Unavailable

        val canDownloadCbz: Boolean
            get() = chapter.content.format == VaultChapterContentFormat.CBZ &&
                state !in EXPORT_BLOCKED_STATES
    }

    data class VaultMetadataEdit(
        val title: String,
        val author: String,
        val artist: String,
        val description: String,
        val status: VaultMangaStatus,
        val labels: List<VaultLabelEdit>,
    )

    data class VaultLabelEdit(
        val identity: String?,
        val name: String,
        val isSensitive: Boolean,
        val assigned: Boolean,
    )

    sealed interface Event {
        data object LoadFailed : Event
        data object CacheFailed : Event
        data class DeleteCompleted(val warningDetail: String? = null) : Event
        data class DeleteFailed(val detail: String) : Event
        data object MetadataPublished : Event
        data class MetadataPublishFailed(val detail: String) : Event
        data class ChapterExported(val filename: String) : Event
        data class ChapterExportFailed(val detail: String) : Event
        data class PendingActionUnavailable(val action: VaultScreenModel.PendingAction) : Event
    }

    data class State(
        val isLoading: Boolean = true,
        val manga: VaultManga? = null,
        val chapters: List<VaultChapterItem> = emptyList(),
        val localCacheUsageBytes: Long = 0,
        val vaultStorageUsageBytes: Long = 0,
        val isDeleting: Boolean = false,
        val mangaLabels: List<VaultLabel> = emptyList(),
        val vaultLabels: List<VaultLabel> = emptyList(),
        val isPublishingMetadata: Boolean = false,
        val pendingLabelIdentities: Set<String> = emptySet(),
        val metadataPublishSuccessCount: Int = 0,
        val coverUri: String? = null,
        val configuredVaultRootPath: String? = null,
        val exportingChapterIds: Set<Long> = emptySet(),
        val exportingThumbnailChapterIds: Set<Long> = emptySet(),
    )

    private fun VaultTransferJob.isCompletedMetadataOperationForCurrentManga(): Boolean {
        return type == VaultTransferType.METADATA_PUBLISH &&
            mangaId == this@VaultMangaScreenModel.mangaId &&
            isTerminal
    }
}

private data class VaultMangaMetadataSnapshot(
    val manga: VaultManga?,
    val mangaLabels: List<VaultLabel>,
    val vaultLabels: List<VaultLabel>,
    val pendingLabelIdentities: Set<String>,
    val isPublishingMetadata: Boolean,
    val terminalJobs: List<VaultTransferJob>,
)

private data class VaultMetadataPendingOverlay(
    val payload: VaultMetadataPublishPayload,
)

private fun List<VaultTransferJob>.latestMetadataOverlay(json: Json): VaultMetadataPendingOverlay? {
    return filter {
        it.type == VaultTransferType.METADATA_PUBLISH &&
            it.isTerminal.not() &&
            it.payloadJson != null
    }
        .maxWithOrNull(compareBy<VaultTransferJob> { it.updatedAt }.thenBy { it.id })
        ?.payloadJson
        ?.let { payloadJson ->
            try {
                VaultMetadataPendingOverlay(json.decodeFromString<VaultMetadataPublishPayload>(payloadJson))
            } catch (_: SerializationException) {
                null
            }
        }
}

private fun VaultManga.withOverlay(overlay: VaultMetadataPendingOverlay?): VaultManga {
    overlay ?: return this
    val payload = overlay.payload
    return copy(
        metadata = VaultMetadata(
            title = payload.title,
            author = payload.author.trim().takeIf(String::isNotBlank),
            artist = payload.artist.trim().takeIf(String::isNotBlank),
            description = payload.description.trim().takeIf(String::isNotBlank),
            status = payload.status,
        ),
    )
}

private fun VaultMetadataPendingOverlay.vaultLabels(authoritativeLabels: List<VaultLabel>): List<VaultLabel> {
    val authoritativeByIdentity = authoritativeLabels.associateBy { it.identity.value }
    val pendingLabels = payload.labels.mapIndexedNotNull { index, label ->
        val name = label.name.trim().takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
        val existing = label.identity?.let(authoritativeByIdentity::get)
        existing?.copy(
            name = name,
            sortKey = VaultMetadata.normalizeTitle(name),
            isSensitive = label.isSensitive,
        ) ?: VaultLabel(
            id = -1L - index,
            vaultId = authoritativeLabels.firstOrNull()?.vaultId ?: -1,
            identity = VaultIdentity(label.identity ?: pendingLabelIdentity(name)),
            name = name,
            sortKey = VaultMetadata.normalizeTitle(name),
            isSensitive = label.isSensitive,
            createdAt = 0,
            updatedAt = 0,
        )
    }
    return (pendingLabels + authoritativeLabels)
        .distinctBy { it.identity.value }
        .sortedBy { it.sortKey }
}

private fun VaultMetadataPendingOverlay.mangaLabels(authoritativeLabels: List<VaultLabel>): List<VaultLabel> {
    val labels = vaultLabels(authoritativeLabels).associateBy { it.identity.value }
    return payload.labels
        .filter { it.assigned }
        .mapNotNull { label ->
            val name = label.name.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            labels[label.identity ?: pendingLabelIdentity(name)]
        }
        .sortedBy { it.sortKey }
}

private fun VaultMetadataPendingOverlay.pendingLabelIdentities(
    authoritativeMangaLabels: List<VaultLabel>,
): Set<String> {
    val authoritativeAssigned = authoritativeMangaLabels.map { it.identity.value }.toSet()
    return payload.labels
        .filter { it.assigned }
        .mapNotNull { label ->
            val name = label.name.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            label.identity
                ?.takeIf { it !in authoritativeAssigned }
                ?: pendingLabelIdentity(name)
        }
        .toSet()
}

internal fun VaultMangaScreenModel.State.remotePathFor(
    item: VaultMangaScreenModel.VaultChapterItem,
): String? {
    val rootPath = configuredVaultRootPath ?: return null
    return vaultChapterRemotePath(rootPath, item.chapter.content.path)
}

internal fun VaultMangaScreenModel.State.remoteThumbnailPathFor(
    item: VaultMangaScreenModel.VaultChapterItem,
): String? {
    val rootPath = configuredVaultRootPath ?: return null
    val thumbnailPath = item.chapter.thumbnail?.path ?: return null
    return vaultChapterRemotePath(rootPath, thumbnailPath)
}

private fun VaultMangaScreenModel.State.labelEdits(): List<VaultMangaScreenModel.VaultLabelEdit> {
    val assigned = mangaLabels.map { it.identity.value }.toSet()
    return vaultLabels.map {
        VaultMangaScreenModel.VaultLabelEdit(
            identity = it.identity.value.takeUnless(::isPendingLabelIdentity),
            name = it.name,
            isSensitive = it.isSensitive,
            assigned = it.identity.value in assigned,
        )
    }
}

private fun pendingLabelIdentity(name: String): String = "$PENDING_LABEL_IDENTITY_PREFIX${VaultMetadata.normalizeTitle(
    name,
)}"

private fun isPendingLabelIdentity(identity: String): Boolean = identity.startsWith(PENDING_LABEL_IDENTITY_PREFIX)

private const val PENDING_LABEL_IDENTITY_PREFIX = "pending:"

internal fun orderVaultMangaDetailChapters(
    chapters: List<VaultMangaScreenModel.VaultChapterItem>,
): List<VaultMangaScreenModel.VaultChapterItem> {
    return chapters.sortedWith { first, second ->
        if (first.chapter.isRecognizedNumber && second.chapter.isRecognizedNumber) {
            second.chapter.chapterNumber.compareTo(first.chapter.chapterNumber)
                .takeIf { it != 0 }
                ?.let { return@sortedWith it }
        }
        second.chapter.title
            .duplicateTitleKey()
            .compareToCaseInsensitiveNaturalOrder(first.chapter.title.duplicateTitleKey())
            .takeIf { it != 0 }
            ?: first.chapter.sourceOrder.compareTo(second.chapter.sourceOrder)
    }
}

internal fun buildVaultChapterItems(
    chapters: List<VaultChapter>,
    cacheStates: List<VaultChapterCacheState>,
    previousItems: List<VaultMangaScreenModel.VaultChapterItem>,
): List<VaultMangaScreenModel.VaultChapterItem> {
    val cacheByChapter = cacheStates.associateBy { it.chapterId }
    val previousByChapter = previousItems.associateBy { it.chapter.id }
    return chapters.map { chapter ->
        val previous = previousByChapter[chapter.id]
        VaultMangaScreenModel.VaultChapterItem(
            chapter = chapter,
            cacheState = cacheByChapter[chapter.id],
            thumbnail = previous
                ?.thumbnail
                ?.takeIf { previous.chapter.thumbnail?.identity == chapter.thumbnail?.identity }
                ?: VaultChapterThumbnailDisplayResult.Unavailable,
        )
    }
}

private fun VaultMangaDeletionResult.toFailureDetail(): String {
    return when (this) {
        is VaultMangaDeletionResult.Deleted -> "Deleted"
        is VaultMangaDeletionResult.DeletedWithCleanupFailures -> {
            "Deleted, but cleanup failed for ${failedPaths.size} remote file(s)"
        }
        VaultMangaDeletionResult.BlockedByActiveTransfer -> "Vault Manga has an active transfer"
        VaultMangaDeletionResult.BlockedByActiveReader -> "Vault Manga is open in the reader"
        VaultMangaDeletionResult.IncompleteConfiguration -> "Incomplete configuration"
        VaultMangaDeletionResult.VaultNotFound -> "Vault not found"
        VaultMangaDeletionResult.MangaNotFound -> "Vault Manga not found"
        VaultMangaDeletionResult.NotVault -> "Remote root is not a Bakalah Content Vault"
        is VaultMangaDeletionResult.UnsupportedOlderVersion -> "Unsupported older layout version $layoutVersion"
        is VaultMangaDeletionResult.UnsupportedNewerVersion -> "Unsupported newer layout version $layoutVersion"
        is VaultMangaDeletionResult.IdentityChanged -> "Remote identity changed to ${remoteIdentity.value}"
        is VaultMangaDeletionResult.RevisionMismatch -> "Vault revision changed to ${currentRevision.id}"
        is VaultMangaDeletionResult.ManifestNotFound -> "Manifest not found: $manifestPath"
        is VaultMangaDeletionResult.IdentityMismatch -> "Manifest identity mismatch: $manifestPath"
        is VaultMangaDeletionResult.Malformed -> "Malformed manifest: $manifestPath"
        VaultMangaDeletionResult.PublishFailed -> "Could not publish Vault deletion"
    }
}

private fun VaultMangaDeletionResult.cleanupWarningDetail(): String? {
    return when (this) {
        is VaultMangaDeletionResult.DeletedWithCleanupFailures ->
            "Cleanup failed for ${failedPaths.size} remote file(s)"
        else -> null
    }
}

private fun String?.toMetadataPublishFailureDetail(): String {
    return when (this) {
        "title_required" -> "Title is required"
        "incomplete_configuration" -> "Incomplete configuration"
        "vault_not_found" -> "Vault not found"
        "manga_not_found" -> "Vault Manga not found"
        "not_vault" -> "Remote root is not a Bakalah Content Vault"
        "unsupported_older_version" -> "Unsupported older layout version"
        "unsupported_newer_version" -> "Unsupported newer layout version"
        "identity_changed" -> "Remote identity changed"
        "revision_mismatch" -> "Vault revision changed"
        "manifest_not_found" -> "Manifest not found"
        "identity_mismatch" -> "Manifest identity mismatch"
        "malformed_manifest" -> "Malformed manifest"
        "label_name_conflict" -> "Vault Label names must be unique"
        "invalid_payload" -> "Invalid metadata operation"
        "missing_payload" -> "Missing metadata operation payload"
        "missing_handler" -> "Missing metadata operation handler"
        else -> "Could not publish Vault metadata"
    }
}

private fun VaultChapterExportResult.toFailureDetail(): String {
    return when (this) {
        is VaultChapterExportResult.Exported -> "Exported"
        VaultChapterExportResult.IncompleteConfiguration -> "Vault configuration incomplete"
        VaultChapterExportResult.RemoteFileNotFound -> "Remote chapter file not found"
        VaultChapterExportResult.IntegrityCheckFailed -> "Downloaded file failed integrity check"
        VaultChapterExportResult.UnsupportedFormat -> "Unsupported chapter format"
        VaultChapterExportResult.SaveFailed -> "Could not download CBZ"
    }
}
