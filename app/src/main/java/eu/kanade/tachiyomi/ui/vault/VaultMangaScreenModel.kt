package eu.kanade.tachiyomi.ui.vault

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.vault.FileVaultTransferLocalStaging
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
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class VaultMangaScreenModel(
    private val mangaId: Long,
    private val repository: VaultRepository = Injekt.get(),
    private val preferences: ContentVaultPreferences = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val application: Application = Injekt.get(),
) : StateScreenModel<VaultMangaScreenModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            val manga = repository.getMangaById(mangaId)
            mutableState.update {
                it.copy(
                    manga = manga,
                    isLoading = manga == null,
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
                            localCacheUsageBytes = chapters.sumOf { item ->
                                item.cacheState
                                    ?.takeIf { state -> state.state == VaultCacheState.CACHED }
                                    ?.sizeBytes
                                    ?: 0L
                            },
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

                val service = transferService(config)
                val localPath = item.chapter.cachePath(manga)
                val jobId = service.enqueue(
                    vaultId = manga.vaultId,
                    type = VaultTransferType.CACHE_CHAPTER,
                    chapterId = item.chapter.id,
                    remotePath = config.rootPath.childPath(item.chapter.content.path),
                    localPath = localPath,
                    sizeBytes = item.chapter.content.sizeBytes,
                    checksumSha256 = item.chapter.content.checksumSha256,
                )
                service.execute(jobId)
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

                val service = transferService(config)
                if (job.state == VaultTransferState.QUEUED) {
                    service.execute(job.id)
                } else {
                    service.retry(job.id)
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
            val service = transferService(config)
            repository.getTransferJobsForVault(manga.vaultId)
                .filter {
                    it.type == VaultTransferType.CACHE_CHAPTER &&
                        it.state == VaultTransferState.QUEUED &&
                        it.chapterId in chapterIds
                }
                .forEach { service.execute(it.id) }
        }.onFailure {
            logcat(LogPriority.ERROR, it)
        }
    }

    private fun transferService(config: WebDavVaultConfig): VaultTransferService {
        return VaultTransferService(
            repository = repository,
            remoteStorage = WebDavVaultTransferStorage(networkHelper, config),
            localStaging = FileVaultTransferLocalStaging(vaultCacheRoot()),
        )
    }

    private fun vaultCacheRoot(): File {
        return File(application.filesDir, VAULT_CACHE_DIR).also { it.mkdirs() }
    }

    private fun VaultChapter.cachePath(manga: VaultManga): String {
        val fileName = content.path.substringAfterLast('/').ifBlank { "${identity.value}.chapter" }
        return "${manga.vaultId}/${manga.identity.value}/${identity.value}/$fileName"
    }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    data class VaultChapterItem(
        val chapter: VaultChapter,
        val cacheState: VaultChapterCacheState?,
    ) {
        val state: VaultCacheState
            get() = cacheState?.state ?: VaultCacheState.VAULT_ONLY
    }

    sealed interface Event {
        data object LoadFailed : Event
        data object CacheFailed : Event
        data class PendingActionUnavailable(val action: VaultScreenModel.PendingAction) : Event
    }

    data class State(
        val isLoading: Boolean = true,
        val manga: VaultManga? = null,
        val chapters: List<VaultChapterItem> = emptyList(),
        val localCacheUsageBytes: Long = 0,
        val vaultStorageUsageBytes: Long = 0,
    )

    private companion object {
        const val VAULT_CACHE_DIR = "vault-cache"
    }
}
