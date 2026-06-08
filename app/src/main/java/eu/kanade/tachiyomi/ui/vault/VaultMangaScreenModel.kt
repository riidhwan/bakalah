package eu.kanade.tachiyomi.ui.vault

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.vault.UniFileVaultTransferLocalStaging
import eu.kanade.tachiyomi.data.vault.VaultCacheEvictionResult
import eu.kanade.tachiyomi.data.vault.VaultCachePolicyService
import eu.kanade.tachiyomi.data.vault.VaultMangaDeletionResult
import eu.kanade.tachiyomi.data.vault.VaultMangaDeletionService
import eu.kanade.tachiyomi.data.vault.VaultMetadataPublishRequest
import eu.kanade.tachiyomi.data.vault.VaultMetadataPublishResult
import eu.kanade.tachiyomi.data.vault.VaultMetadataPublishService
import eu.kanade.tachiyomi.data.vault.VaultTransferResult
import eu.kanade.tachiyomi.data.vault.VaultTransferService
import eu.kanade.tachiyomi.data.vault.WebDavVaultTransferStorage
import eu.kanade.tachiyomi.network.NetworkHelper
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
    private val metadataPublishService: VaultMetadataPublishService = Injekt.get(),
) : StateScreenModel<VaultMangaScreenModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            val manga = repository.getMangaById(mangaId)
            val mangaLabels = manga?.let { loadedManga -> repository.getLabelsForManga(loadedManga.id) }.orEmpty()
            mutableState.update {
                it.copy(
                    manga = manga,
                    isLoading = manga == null,
                    mangaLabels = mangaLabels,
                )
            }
            if (manga == null) {
                _events.send(Event.LoadFailed)
                return@launchIO
            }
            resumeQueuedCacheJobs(manga)

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
                            chapters = chapters,
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

    private suspend fun resumeQueuedCacheJobs(manga: VaultManga) {
        runCatching {
            val config = preferences.getWebDavConfig()
            if (!config.isComplete) return
            val chapterIds = repository.getChapters(manga.id).map { it.id }.toSet()
            val localStaging = localStaging() ?: return
            val service = transferService(config, localStaging)
            val cachePolicy = cachePolicyService(localStaging)
            repository.getTransferJobsForVault(manga.vaultId)
                .filter {
                    it.type == VaultTransferType.CACHE_CHAPTER &&
                        it.state == VaultTransferState.QUEUED &&
                        it.chapterId in chapterIds
                }
                .forEach {
                    if (service.execute(it.id) == VaultTransferResult.Succeeded) {
                        cachePolicy.enforceLimit(manga.vaultId)
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
                deletionService.moveToTrash(manga.id)
            }.getOrElse {
                logcat(LogPriority.ERROR, it)
                VaultMangaDeletionResult.PublishFailed
            }
            when (result) {
                is VaultMangaDeletionResult.Deleted -> {
                    localStaging()?.let { localStaging ->
                        cachePolicyService(localStaging).evictManga(manga.id)
                    }
                    _events.send(Event.DeleteCompleted)
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
                        labelNames = edit.labelNames,
                    ),
                )
            }.getOrElse {
                logcat(LogPriority.ERROR, it)
                VaultMetadataPublishResult.PublishFailed
            }
            when (result) {
                VaultMetadataPublishResult.Published -> {
                    reloadMangaMetadata()
                    _events.send(Event.MetadataPublished)
                }
                else -> _events.send(Event.MetadataPublishFailed(result.toFailureDetail()))
            }
            mutableState.update { it.copy(isPublishingMetadata = false) }
        }
    }

    private suspend fun reloadMangaMetadata() {
        val manga = repository.getMangaById(mangaId) ?: return
        val mangaLabels = repository.getLabelsForManga(manga.id)
        mutableState.update {
            it.copy(
                manga = manga,
                mangaLabels = mangaLabels,
            )
        }
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
        val labelNames: List<String>,
    )

    sealed interface Event {
        data object LoadFailed : Event
        data object CacheFailed : Event
        data object DeleteCompleted : Event
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
        val isPublishingMetadata: Boolean = false,
    )
}

private fun VaultMangaDeletionResult.toFailureDetail(): String {
    return when (this) {
        is VaultMangaDeletionResult.Deleted -> "Deleted"
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
        VaultMetadataPublishResult.PublishFailed -> "Could not publish Vault metadata"
    }
}
