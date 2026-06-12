package eu.kanade.tachiyomi.data.vault.reader

import eu.kanade.tachiyomi.data.vault.cache.VaultCachePolicyService
import eu.kanade.tachiyomi.data.vault.transfer.VaultTransferLocalStaging
import eu.kanade.tachiyomi.data.vault.transfer.VaultTransferResult
import eu.kanade.tachiyomi.data.vault.transfer.VaultTransferService
import eu.kanade.tachiyomi.data.vault.transfer.vaultTransferIntegrity
import kotlinx.coroutines.delay
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

class VaultReaderOpenService(
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val transferServiceFactory: () -> VaultTransferService?,
    private val localStaging: VaultTransferLocalStaging,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun prepareChapter(mangaId: Long, chapterId: Long): VaultReaderOpenResult {
        val manga = repository.getMangaById(mangaId) ?: return VaultReaderOpenResult.NotFound
        val chapter = repository.getChapters(mangaId).firstOrNull { it.id == chapterId }
            ?: return VaultReaderOpenResult.NotFound

        val cached = verifyCached(chapter)
        val cachedPath = cached?.localPath
        if (cachedPath != null) {
            return VaultReaderOpenResult.Ready(manga, chapter, cachedPath)
        }

        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultReaderOpenResult.Failed("incomplete configuration")
        val service = transferServiceFactory() ?: return VaultReaderOpenResult.Failed("cache unavailable")

        val job = repository.getTransferJobsForVault(manga.vaultId)
            .lastOrNull {
                it.type == VaultTransferType.CACHE_CHAPTER &&
                    it.chapterId == chapter.id &&
                    (it.state == VaultTransferState.QUEUED || it.state == VaultTransferState.RUNNING)
            }

        val result = if (job != null) {
            when (job.state) {
                VaultTransferState.QUEUED -> service.execute(job.id)
                VaultTransferState.RUNNING -> awaitRunningJob(job.id)
                else -> VaultTransferResult.NotRetryable(job.state)
            }
        } else {
            val cachePolicy = VaultCachePolicyService(repository, localStaging, preferences, now)
            val jobId = service.enqueue(
                vaultId = manga.vaultId,
                type = VaultTransferType.CACHE_CHAPTER,
                chapterId = chapter.id,
                remotePath = config.rootPath.childPath(chapter.content.path),
                localPath = cachePolicy.cachePath(manga, chapter),
                sizeBytes = chapter.content.sizeBytes,
                checksumSha256 = chapter.content.checksumSha256,
            )
            service.execute(jobId)
        }

        if (result != VaultTransferResult.Succeeded) {
            val completed = result as? VaultTransferResult.AlreadyFinished
            if (completed?.state != VaultTransferState.SUCCEEDED) {
                return VaultReaderOpenResult.Failed(result.failureReason())
            }
        }

        val verifiedPath = verifyCached(chapter)?.localPath
            ?: return VaultReaderOpenResult.Failed("cache verification failed")
        return VaultReaderOpenResult.Ready(manga, chapter, verifiedPath)
    }

    private suspend fun verifyCached(chapter: VaultChapter): VaultChapterCacheState? {
        val state = repository.getCacheState(chapter.id) ?: return null
        val localPath = state.localPath
        if (state.state != VaultCacheState.CACHED || localPath == null) return null

        val bytes = localStaging.read(localPath)
        if (bytes == null) {
            repository.upsertCacheState(
                state.copy(
                    state = VaultCacheState.VAULT_ONLY,
                    localPath = null,
                    sizeBytes = null,
                    checksumSha256 = null,
                    lastVerifiedAt = null,
                    updatedAt = now(),
                    failureReason = null,
                ),
            )
            return null
        }

        val integrity = bytes.vaultTransferIntegrity()
        if (
            integrity.sizeBytes != chapter.content.sizeBytes ||
            integrity.checksumSha256 != chapter.content.checksumSha256
        ) {
            repository.upsertCacheState(
                state.copy(
                    state = VaultCacheState.INTEGRITY_FAULT,
                    sizeBytes = integrity.sizeBytes,
                    checksumSha256 = integrity.checksumSha256,
                    lastVerifiedAt = now(),
                    updatedAt = now(),
                    failureReason = "integrity mismatch",
                ),
            )
            return null
        }

        val verified = state.copy(
            sizeBytes = integrity.sizeBytes,
            checksumSha256 = integrity.checksumSha256,
            lastVerifiedAt = now(),
            updatedAt = now(),
            failureReason = null,
        )
        repository.upsertCacheState(verified)
        return verified
    }

    private suspend fun awaitRunningJob(jobId: Long): VaultTransferResult {
        repeat(RUNNING_JOB_POLL_ATTEMPTS) {
            delay(RUNNING_JOB_POLL_DELAY_MS)
            val job = repository.getTransferJob(jobId) ?: return VaultTransferResult.NotFound
            if (job.isTerminal) return VaultTransferResult.AlreadyFinished(job.state)
        }
        return VaultTransferResult.Failed("cache job still running")
    }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    private fun VaultTransferResult.failureReason(): String {
        return when (this) {
            VaultTransferResult.Succeeded -> ""
            VaultTransferResult.Cancelled -> "cache cancelled"
            VaultTransferResult.NotFound -> "cache job not found"
            is VaultTransferResult.Failed -> reason
            is VaultTransferResult.IntegrityFault -> reason
            is VaultTransferResult.AlreadyFinished -> "cache job finished with $state"
            is VaultTransferResult.NotRetryable -> "cache job is not retryable from $state"
        }
    }

    private companion object {
        const val RUNNING_JOB_POLL_ATTEMPTS = 240
        const val RUNNING_JOB_POLL_DELAY_MS = 500L
    }
}

sealed interface VaultReaderOpenResult {
    data class Ready(
        val manga: VaultManga,
        val chapter: VaultChapter,
        val localPath: String,
    ) : VaultReaderOpenResult

    data object NotFound : VaultReaderOpenResult
    data class Failed(val reason: String) : VaultReaderOpenResult
}
