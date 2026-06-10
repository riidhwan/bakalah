package eu.kanade.tachiyomi.ui.vault

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.vault.UniFileVaultTransferLocalStaging
import eu.kanade.tachiyomi.data.vault.VaultCacheEvictionResult
import eu.kanade.tachiyomi.data.vault.VaultCachePolicyService
import eu.kanade.tachiyomi.data.vault.VaultCoverPublishService
import eu.kanade.tachiyomi.data.vault.VaultLabelPublishEdit
import eu.kanade.tachiyomi.data.vault.VaultMangaDeletionResult
import eu.kanade.tachiyomi.data.vault.VaultMangaDeletionService
import eu.kanade.tachiyomi.data.vault.VaultMetadataPublishRequest
import eu.kanade.tachiyomi.data.vault.VaultMetadataPublishResult
import eu.kanade.tachiyomi.data.vault.VaultMetadataPublishService
import eu.kanade.tachiyomi.data.vault.VaultTransferResult
import eu.kanade.tachiyomi.data.vault.VaultTransferService
import eu.kanade.tachiyomi.data.vault.WebDavVaultTransferStorage
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.vault.interactor.duplicateTitleKey
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VaultMangaScreenModel(
    private val mangaId: Long,
    private val repository: VaultRepository = Injekt.get(),
    private val preferences: ContentVaultPreferences = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val deletionService: VaultMangaDeletionService = Injekt.get(),
    private val coverPublishService: VaultCoverPublishService = Injekt.get(),
    private val metadataPublishService: VaultMetadataPublishService = Injekt.get(),
) : StateScreenModel<VaultMangaScreenModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            val manga = repository.getMangaById(mangaId)
            val mangaLabels = manga?.let { loadedManga -> repository.getLabelsForManga(loadedManga.id) }.orEmpty()
            val vaultLabels = manga?.let { loadedManga -> repository.getLabels(loadedManga.vaultId) }.orEmpty()
            mutableState.update {
                it.copy(
                    manga = manga,
                    isLoading = manga == null,
                    mangaLabels = mangaLabels,
                    vaultLabels = vaultLabels,
                )
            }
            if (manga == null) {
                _events.send(Event.LoadFailed)
                return@launchIO
            }
            reloadCoverCache()
            recoverInterruptedCacheJobs(manga)

            combine(
                repository.getChaptersAsFlow(mangaId),
                repository.getCacheStatesForMangaAsFlow(mangaId),
            ) { chapters, cacheStates ->
                val cacheByChapter = cacheStates.associateBy { it.chapterId }
                chapters.map { chapter ->
                    VaultChapterItem(
                        chapter = chapter,
                        cacheState = cacheByChapter[chapter.id],
                    )
                }
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
                            vaultStorageUsageBytes = chapters.sumOf { item -> item.chapter.content.sizeBytes },
                        )
                    }
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
                        cachePolicy.enforceLimit(manga.vaultId)
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
                        cachePolicy.enforceLimit(manga.vaultId)
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
                    cachePolicy.enforceLimit(manga.vaultId)
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
            mutableState.update { it.copy(isPublishingMetadata = true) }
            val result = runCatching {
                metadataPublishService.publish(
                    VaultMetadataPublishRequest(
                        mangaId = mangaId,
                        title = edit.title,
                        author = edit.author,
                        artist = edit.artist,
                        description = edit.description,
                        status = edit.status,
                        labels = edit.labels.map {
                            VaultLabelPublishEdit(
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
                VaultMetadataPublishResult.PublishFailed
            }
            when (result) {
                VaultMetadataPublishResult.Published -> {
                    reloadMangaMetadata()
                    mutableState.update {
                        it.copy(metadataPublishSuccessCount = it.metadataPublishSuccessCount + 1)
                    }
                    _events.send(Event.MetadataPublished)
                }
                else -> _events.send(Event.MetadataPublishFailed(result.toFailureDetail()))
            }
            mutableState.update { it.copy(isPublishingMetadata = false) }
        }
    }

    fun assignLabel(label: VaultLabel) {
        publishLabelEdit(
            editLabel = { edit ->
                edit.copy(assigned = edit.identity == label.identity.value || edit.assigned)
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
                if (edit.identity == label.identity.value) {
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
                if (edit.identity == label.identity.value) {
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

    private suspend fun reloadMangaMetadata() {
        val manga = repository.getMangaById(mangaId) ?: return
        val mangaLabels = repository.getLabelsForManga(manga.id)
        val vaultLabels = repository.getLabels(manga.vaultId)
        mutableState.update {
            it.copy(
                manga = manga,
                mangaLabels = mangaLabels,
                vaultLabels = vaultLabels,
            )
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
    }

    data class VaultChapterItem(
        val chapter: VaultChapter,
        val cacheState: VaultChapterCacheState?,
    ) {
        val state: VaultCacheState
            get() = cacheState?.state ?: VaultCacheState.VAULT_ONLY
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
        val metadataPublishSuccessCount: Int = 0,
        val coverUri: String? = null,
    )
}

private fun VaultMangaScreenModel.State.labelEdits(): List<VaultMangaScreenModel.VaultLabelEdit> {
    val assigned = mangaLabels.map { it.identity.value }.toSet()
    return vaultLabels.map {
        VaultMangaScreenModel.VaultLabelEdit(
            identity = it.identity.value,
            name = it.name,
            isSensitive = it.isSensitive,
            assigned = it.identity.value in assigned,
        )
    }
}

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

private fun VaultMetadataPublishResult.toFailureDetail(): String {
    return when (this) {
        VaultMetadataPublishResult.Published -> "Published"
        VaultMetadataPublishResult.TitleRequired -> "Title is required"
        VaultMetadataPublishResult.IncompleteConfiguration -> "Incomplete configuration"
        VaultMetadataPublishResult.VaultNotFound -> "Vault not found"
        VaultMetadataPublishResult.MangaNotFound -> "Vault Manga not found"
        VaultMetadataPublishResult.NotVault -> "Remote root is not a Bakalah Content Vault"
        is VaultMetadataPublishResult.UnsupportedOlderVersion -> "Unsupported older layout version $layoutVersion"
        is VaultMetadataPublishResult.UnsupportedNewerVersion -> "Unsupported newer layout version $layoutVersion"
        is VaultMetadataPublishResult.IdentityChanged -> "Remote identity changed to ${remoteIdentity.value}"
        is VaultMetadataPublishResult.RevisionMismatch -> "Vault revision changed to ${currentRevision.id}"
        is VaultMetadataPublishResult.ManifestNotFound -> "Manifest not found: $manifestPath"
        is VaultMetadataPublishResult.IdentityMismatch -> "Manifest identity mismatch: $manifestPath"
        is VaultMetadataPublishResult.Malformed -> "Malformed manifest: $manifestPath"
        VaultMetadataPublishResult.LabelNameConflict -> "Vault Label names must be unique"
        VaultMetadataPublishResult.PublishFailed -> "Could not publish Vault metadata"
    }
}
